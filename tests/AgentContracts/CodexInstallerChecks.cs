using RemoteTool.WebApi.Services.Codex;

/// <summary>The managed installer must only run where it can succeed, install
/// into the official npm prefix, and only report ready after the downloaded
/// CLI passes the protocol check this app depends on.</summary>
internal static class CodexInstallerChecks
{
    private static void Check(bool value, string message) { if (!value) throw new Exception(message); }

    public static async Task Run()
    {
        var root = Path.Combine(Path.GetTempPath(), "codex-installer-" + Guid.NewGuid().ToString("N"));
        var globalRoot = Path.Combine(root, "npm-global");
        Directory.CreateDirectory(root);
        try
        {
            var refused = new CodexInstaller(root, null, (_, _, _) => Task.FromResult(new CodexInstaller.Output(0, "")), default);
            Check(!refused.CanInstall, "installer reports itself unavailable without npm");
            try { refused.Start(); throw new Exception("installer started without npm"); }
            catch (InvalidOperationException) { }

            var calls = new List<string>();
            var binaryName = OperatingSystem.IsWindows() ? "codex.exe" : "codex";
            var vendor = Path.Combine(globalRoot, "@openai", "codex", "node_modules", "@openai",
                OperatingSystem.IsWindows() ? "codex-win32-x64" : "codex-linux-x64", "vendor", "target", "bin");
            Func<string, string[], CancellationToken, Task<CodexInstaller.Output>> run = (file, args, _) =>
            {
                calls.Add(file + " " + string.Join(' ', args));
                if (args.Length > 0 && args[0] == "install")
                {
                    Check(args.Contains("--global"), "installer uses the official global prefix instead of a private one");
                    Check(args[Array.IndexOf(args, "--registry") + 1] == "https://registry.npmjs.org", "installer pins the official registry");
                    Directory.CreateDirectory(vendor);
                    var binary = Path.Combine(vendor, binaryName);
                    File.WriteAllBytes(binary, new byte[1_100_000]);
                    if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(binary, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
                    if (OperatingSystem.IsWindows())
                    {
                        File.WriteAllText(Path.Combine(vendor, "codex-code-mode-host.exe"), "");
                        var resources = Path.Combine(Path.GetDirectoryName(vendor)!, "codex-resources");
                        Directory.CreateDirectory(resources);
                        File.WriteAllText(Path.Combine(resources, "codex-command-runner.exe"), "");
                    }
                    return Task.FromResult(new CodexInstaller.Output(0, "added 1 package"));
                }
                if (args.Length == 2 && args[0] == "root" && args[1] == "--global")
                    return Task.FromResult(new CodexInstaller.Output(0, globalRoot + Environment.NewLine));
                if (args.Length == 1 && args[0] == "--version")
                    return Task.FromResult(new CodexInstaller.Output(0, "codex-cli 0.166.0"));
                if (args.Length >= 3 && args[0] == "app-server" && args[1] == "generate-json-schema")
                {
                    var output = args[Array.IndexOf(args, "--out") + 1];
                    foreach (var name in new[] { "v2/ThreadStartParams.json", "v2/ThreadTurnsListParams.json", "v2/TurnStartParams.json" })
                    {
                        var path = Path.Combine(output, name);
                        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                        File.WriteAllText(path, "{}");
                    }
                    return Task.FromResult(new CodexInstaller.Output(0, ""));
                }
                return Task.FromResult(new CodexInstaller.Output(1, "unexpected " + string.Join(' ', args)));
            };

            var installer = new CodexInstaller(root, "/usr/bin/npm", run, default);
            Check(installer.CanInstall, "installer advertises itself when npm is available");
            var started = installer.Start();
            Check(started.Phase == "installing", "install starts in the installing phase");
            await installer.Completion;

            var done = installer.Current;
            Check(done.Phase == "ready", "successful install reaches ready, got " + done.Phase + ": " + done.Message);
            Check(done.Version == "0.166.0", "installer records the verified CLI version");
            Check(calls.Any(x => x.Contains("generate-json-schema")), "installer verifies the app-server protocol before promoting");
            var record = File.ReadAllText(Path.Combine(root, "executable.json"));
            Check(record.Contains("\"source\":\"npm-global\""), "installer records the official npm source");
            Check(record.Contains(vendor.Replace("\\", "\\\\")), "installer records the executable inside the global prefix");
            Check(!Directory.Exists(Path.Combine(root, "runtime-" + done.Id)), "no private runtime directory is created any more");

            var reopened = new CodexInstaller(root, "/usr/bin/npm", run, default);
            Check(reopened.Current.Phase == "ready" && reopened.Current.Version == "0.166.0", "status survives a restart with its fields intact");

            var broken = Path.Combine(root, "broken");
            var failing = new CodexInstaller(broken, "/usr/bin/npm", (file, args, _) => Task.FromResult(new CodexInstaller.Output(1, "boom")), default);
            failing.Start();
            await failing.Completion;
            Check(failing.Current.Phase == "failed", "a failed install reports failure");

            var denied = new CodexInstaller(Path.Combine(root, "denied"), "/usr/bin/npm",
                (file, args, _) => Task.FromResult(new CodexInstaller.Output(1, "npm ERR! code EACCES")), default);
            denied.Start();
            await denied.Completion;
            Check(denied.Current.Message.Contains("权限"), "permission failures explain the global-prefix write problem");

            Console.WriteLine("Codex installer checks passed: gated start, official global prefix, pinned registry, protocol verification, restart-safe status, honest failures");
        }
        finally
        {
            try { Directory.Delete(root, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
}
