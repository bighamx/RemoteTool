using RemoteTool.WebApi.Services.Codex;

/// <summary>The managed installer must only run where it can succeed, and must
/// finish with a verified executable instead of a half-populated directory.</summary>
internal static class CodexInstallerChecks
{
    private static void Check(bool value, string message) { if (!value) throw new Exception(message); }

    public static async Task Run()
    {
        var root = Path.Combine(Path.GetTempPath(), "codex-installer-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            var refused = new CodexInstaller(root, null, (_, _, _) => Task.FromResult(new CodexInstaller.Output(0, "")), default);
            Check(!refused.CanInstall, "installer reports itself unavailable without npm");
            try { refused.Start(); throw new Exception("installer started without npm"); }
            catch (InvalidOperationException) { }

            var calls = new List<string>();
            Func<string, string[], CancellationToken, Task<CodexInstaller.Output>> run = (file, args, _) =>
            {
                calls.Add(file + " " + string.Join(' ', args));
                if (args.Length > 0 && args[0] == "install")
                {
                    var prefix = args[Array.IndexOf(args, "--prefix") + 1];
                    Check(args[Array.IndexOf(args, "--registry") + 1] == "https://registry.npmjs.org", "installer pins the official registry");
                    var binary = Path.Combine(prefix, "node_modules", ".bin", "codex");
                    Directory.CreateDirectory(Path.GetDirectoryName(binary)!);
                    File.WriteAllBytes(binary, new byte[1_100_000]);
                    return Task.FromResult(new CodexInstaller.Output(0, ""));
                }
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
            Check(File.Exists(Path.Combine(root, "executable.json")), "installer records the promoted executable");
            Check(Directory.GetDirectories(root, "runtime-*").Length == 1, "installer promotes exactly one runtime directory");

            var reopened = new CodexInstaller(root, "/usr/bin/npm", run, default);
            Check(reopened.Current.Phase == "ready" && reopened.Current.Version == "0.166.0", "status survives a restart with its fields intact");

            var broken = Path.Combine(root, "broken");
            var failing = new CodexInstaller(broken, "/usr/bin/npm", (file, args, _) => Task.FromResult(new CodexInstaller.Output(1, "boom")), default);
            failing.Start();
            await failing.Completion;
            Check(failing.Current.Phase == "failed", "a failed install reports failure");
            Check(!Directory.Exists(Path.Combine(broken, "runtime-" + failing.Current.Id)) && Directory.GetDirectories(broken, "runtime-*").Length == 0,
                "a failed install leaves no partial runtime behind");

            Console.WriteLine("Codex installer checks passed: gated start, pinned registry, protocol verification, restart-safe status, clean failure");
        }
        finally
        {
            try { Directory.Delete(root, true); } catch (IOException) { } catch (UnauthorizedAccessException) { }
        }
    }
}
