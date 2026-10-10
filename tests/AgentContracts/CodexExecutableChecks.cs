using RemoteTool.WebApi.Services.Codex;

internal static class CodexExecutableChecks
{
    public static void Run() {
        var profile = Path.Combine(Path.GetTempPath(), "codex-update-" + Guid.NewGuid().ToString("N"));
        var home = Path.Combine(profile, ".codex");
        var bundled = Path.Combine(profile, "AppData", "Local", "OpenAI", "Codex", "bin");
        string Bundle(string name, bool host, bool runner) {
            var directory = Path.Combine(bundled, name); Directory.CreateDirectory(directory);
            var exe = Path.Combine(directory, "codex.exe"); File.WriteAllText(exe, "fixture");
            if (host) File.WriteAllText(Path.Combine(directory, "codex-code-mode-host.exe"), "fixture");
            if (runner) File.WriteAllText(Path.Combine(directory, "codex-command-runner.exe"), "fixture");
            return exe;
        }
        void Check(bool value, string name) { if (!value) throw new Exception(name); Console.WriteLine("PASS: " + name); }
        Directory.CreateDirectory(home);
        try {
            var old = Bundle("old", true, true);
            var next = Bundle("next", true, true);
            var downloading = Bundle("downloading", true, false);
            File.SetLastWriteTimeUtc(next, DateTime.UtcNow.AddMinutes(-1));
            File.SetLastWriteTimeUtc(downloading, DateTime.UtcNow);
            Check(CodexExecutable.Resolve(old, home) == old, "a complete configured runtime remains stable during updates");
            File.Delete(Path.Combine(Path.GetDirectoryName(old)!, "codex-code-mode-host.exe"));
            Check(!CodexExecutable.Complete(old), "a surviving codex.exe does not mask a removed tool host");
            Check(CodexExecutable.Resolve(old, home) == next, "stale runtime resolves to complete bundle and skips newer partial download");
            Check(CodexExecutable.Resolve(Path.Combine(bundled, "missing", "codex.exe"), home) == next, "deleted executable recovers from the same user profile");
            File.Delete(Path.Combine(Path.GetDirectoryName(next)!, "codex-command-runner.exe"));
            try { CodexExecutable.Resolve(old, home); throw new Exception("partial bundle accepted"); }
            catch (CodexError error) { Check(error.Code == "codex_tools_missing" && error.Delivery == "rejected", "partial updates reject before accepting a message"); }
        } finally { Directory.Delete(profile, true); }
    }
}
