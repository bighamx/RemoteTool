using System.Diagnostics;
using System.Xml.Linq;

namespace ChuckieHelper.WebApi.Services.Codex;

internal sealed class CodexDesktopRestart
{
    internal sealed record Target(int Id, DateTime Started, string Path, bool Desktop);
    private readonly List<Target> targets;
    private CodexDesktopRestart(List<Target> targets) { this.targets = targets; }
    public static CodexDesktopRestart Capture(IEnumerable<int> excluded) {
        var skip = excluded.ToHashSet(); var ownSession = Process.GetCurrentProcess().SessionId;
        var targets = new List<Target>();
        foreach (var process in Process.GetProcessesByName("codex").Concat(Process.GetProcessesByName("ChatGPT"))) using (process) {
            try {
                if (skip.Contains(process.Id) || process.SessionId != ownSession) continue;
                var path = process.MainModule?.FileName;
                if (path == null) throw new CodexError("无法核对旧 Codex 进程，尚未切换身份", 409);
                var packagedDesktop = path.Contains("OpenAI.Codex_", StringComparison.OrdinalIgnoreCase) && Path.GetFileName(path).Equals("ChatGPT.exe", StringComparison.OrdinalIgnoreCase);
                // A standalone ChatGPT installation is not a Codex desktop process.
                if (process.ProcessName.Equals("ChatGPT", StringComparison.OrdinalIgnoreCase) && !packagedDesktop) continue;
                var desktop = packagedDesktop || process.MainWindowHandle != IntPtr.Zero && !path.Contains("\\bin\\", StringComparison.OrdinalIgnoreCase);
                targets.Add(new Target(process.Id, process.StartTime.ToUniversalTime(), path, desktop));
            } catch (InvalidOperationException) { }
        }
        return new(targets);
    }
    public async Task Stop() {
        // Stop desktop parents first, including their old app-server children.
        foreach (var target in targets.OrderByDescending(x => x.Desktop)) {
            try {
                using var process = Process.GetProcessById(target.Id);
                if (process.HasExited) continue;
                if (process.StartTime.ToUniversalTime() != target.Started || !string.Equals(process.MainModule?.FileName, target.Path, StringComparison.OrdinalIgnoreCase))
                    throw new CodexError("进程已变化，停止切换以避免误结束其他程序", 409);
                process.Kill(entireProcessTree: true);
                await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(10));
            } catch (ArgumentException) { }
            catch (InvalidOperationException) { }
        }
    }
    public void Restart() {
        foreach (var path in targets.Where(x => x.Desktop).Select(x => x.Path).Distinct(StringComparer.OrdinalIgnoreCase)) {
            var application = ApplicationId(path);
            if (application != null) {
                var info = new ProcessStartInfo("explorer.exe") { UseShellExecute = true };
                info.ArgumentList.Add("shell:AppsFolder\\" + application); Process.Start(info);
            } else Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
        }
    }
    private static string ApplicationId(string executable) {
        for (var folder = Directory.GetParent(executable); folder != null; folder = folder.Parent) {
            var manifest = Path.Combine(folder.FullName, "AppxManifest.xml");
            if (!File.Exists(manifest)) continue;
            var xml = XDocument.Load(manifest);
            var identity = xml.Descendants().FirstOrDefault(e => e.Name.LocalName == "Identity");
            var relative = Path.GetRelativePath(folder.FullName, executable).Replace('\\', '/');
            var app = xml.Descendants().FirstOrDefault(e => e.Name.LocalName == "Application" && string.Equals(e.Attribute("Executable")?.Value.Replace('\\', '/'), relative, StringComparison.OrdinalIgnoreCase));
            if (identity == null || app == null) return null;
            var publisher = folder.Name.Split('_').Last();
            return identity.Attribute("Name")!.Value + "_" + publisher + "!" + app.Attribute("Id")!.Value;
        }
        return null;
    }
}
