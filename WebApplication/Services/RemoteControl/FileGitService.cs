using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.RemoteControl;

public static class FileGitService
{
    public sealed record Request(string Path, string Action, string Message = "");
    public sealed record Result(bool Success, string Output, string Error = "", string Code = "");
    private sealed class Job { public Result Result; public DateTime Created = DateTime.UtcNow; }
    private static readonly ConcurrentDictionary<string, Job> jobs = new();
    private static readonly ConcurrentDictionary<string, string> repositories = new(OperatingSystem.IsWindows() ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal);
    private static string Root => Path.Combine(RemoteToolPaths.ProgramData, "file-git-jobs");

    private static string Repository(string metadata) {
        if (!System.IO.Path.IsPathFullyQualified(metadata ?? "")) throw new ArgumentException("请选择仓库的 .git 文件夹");
        var path = System.IO.Path.TrimEndingDirectorySeparator(System.IO.Path.GetFullPath(metadata));
        if (!System.IO.Path.GetFileName(path).Equals(".git", StringComparison.OrdinalIgnoreCase) ||
            !Directory.Exists(path) && !File.Exists(path)) throw new ArgumentException("请选择仓库的 .git 文件夹或文件");
        return System.IO.Path.GetDirectoryName(path)!;
    }
    public static string Start(Request request) {
        if (request.Action is not ("status" or "commit-push" or "push" or "pull")) throw new ArgumentException("不支持此 Git 操作");
        if (request.Action == "commit-push" && (string.IsNullOrWhiteSpace(request.Message) || request.Message.Length > 2000 || request.Message.Contains('\0')))
            throw new ArgumentException("请填写提交说明（最多 2000 字）");
        var repo = Repository(request.Path);
        var id = Guid.NewGuid().ToString("N");
        if (!repositories.TryAdd(repo, id)) throw new InvalidOperationException("此仓库正在执行 Git 操作，请稍后重试");
        var job = new Job(); jobs[id] = job;
        _ = Execute(id, repo, request, job);
        foreach (var old in jobs.Where(x => x.Value.Result != null && x.Value.Created < DateTime.UtcNow.AddHours(-1))) jobs.TryRemove(old.Key, out _);
        return id;
    }
    public static object Status(string id) => jobs.TryGetValue(id, out var job)
        ? new { done = job.Result != null, result = job.Result } : throw new KeyNotFoundException("Git 操作记录已失效，请刷新仓库状态；不要直接重复提交");

    private static async Task Execute(string id, string repo, Request request, Job job) {
        var directory = System.IO.Path.Combine(Root, id);
        Process worker = null;
        try {
            if (OperatingSystem.IsWindows() && InteractiveProcessLauncher.IsRunningInSession0) {
                Directory.CreateDirectory(directory);
                var acl = new DirectorySecurity(); acl.SetAccessRuleProtection(true, false);
                foreach (var sid in new[] { InteractiveProcessLauncher.GetInteractiveUserSid(), "S-1-5-18" }.Distinct())
                    acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid), FileSystemRights.FullControl,
                        InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
                new DirectoryInfo(directory).SetAccessControl(acl);
                await File.WriteAllTextAsync(System.IO.Path.Combine(directory, "request.json"), JsonSerializer.Serialize(request));
                var dll = InteractiveProcessLauncher.GetApplicationDllPath();
                var dotnet = InteractiveProcessLauncher.GetDotnetPath();
                var launch = InteractiveProcessLauncher.LaunchInInteractiveSession($"\"{dotnet}\" \"{dll}\" --file-git-worker \"{id}\"", System.IO.Path.GetDirectoryName(dll));
                if (!launch.Success) throw new IOException("无法以当前电脑用户运行 Git，请先登录电脑桌面");
                worker = Process.GetProcessById(launch.ProcessId);
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(150));
                await worker.WaitForExitAsync(timeout.Token);
                var result = System.IO.Path.Combine(directory, "result.json");
                if (!File.Exists(result)) throw new IOException("Git 操作未返回结果，请查看仓库状态后再操作");
                job.Result = JsonSerializer.Deserialize<Result>(await File.ReadAllTextAsync(result))!;
            } else job.Result = await Run(request);
        } catch (Exception error) {
            if (worker != null) try { if (!worker.HasExited) worker.Kill(true); } catch { }
            job.Result = new(false, "", error is OperationCanceledException ? "Git 操作超时，请核对仓库状态；提交可能已完成，推送可能未完成。" : error.Message);
        } finally {
            worker?.Dispose(); repositories.TryRemove(repo, out _);
            // Only remove the freshly created job directory beneath our exact root.
            if (FilePathPolicy.IsWithin(Root, directory, false) && Directory.Exists(directory))
                try { Directory.Delete(directory, true); } catch { }
        }
    }
    public static async Task Worker(string id) {
        if (!System.Text.RegularExpressions.Regex.IsMatch(id, "^[a-f0-9]{32}$")) throw new ArgumentException("无效 Git 操作标识");
        var directory = System.IO.Path.Combine(Root, id);
        Result result;
        try {
            var request = JsonSerializer.Deserialize<Request>(await File.ReadAllTextAsync(System.IO.Path.Combine(directory, "request.json")))!;
            result = await Run(request);
        } catch (Exception error) { result = new(false, "", error.Message); }
        await File.WriteAllTextAsync(System.IO.Path.Combine(directory, "result.json"), JsonSerializer.Serialize(result));
    }
    private static string FindGit() {
        var name = OperatingSystem.IsWindows() ? "git.exe" : "git";
        var paths = (Environment.GetEnvironmentVariable("PATH") ?? "").Split(System.IO.Path.PathSeparator)
            .Where(x => !string.IsNullOrWhiteSpace(x)).Select(x => System.IO.Path.Combine(x.Trim('"'), name)).ToList();
        if (OperatingSystem.IsWindows()) foreach (var root in new[] { Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
            Environment.GetFolderPath(Environment.SpecialFolder.ProgramFilesX86), System.IO.Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs") })
            paths.Add(System.IO.Path.Combine(root, "Git", "cmd", name));
        return paths.FirstOrDefault(File.Exists);
    }
    private static async Task<string> ReadBounded(StreamReader reader) {
        var buffer = new char[4096]; var text = new StringBuilder(); int count;
        while ((count = await reader.ReadAsync(buffer)) > 0) {
            if (text.Length < 24000) text.Append(buffer, 0, Math.Min(count, 24000 - text.Length));
        }
        return text.ToString();
    }
    private static async Task<Result> Run(Request request) {
        var repo = Repository(request.Path);
        var git = FindGit();
        if (git == null) return new(false, "", "此设备未找到 Git，请在电脑上安装 Git 并加入 PATH，然后重试（Windows：安装 Git for Windows）。", "git_not_installed");
        var log = new StringBuilder();
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(120));
        async Task<(int Exit, string Output)> Command(params string[] arguments) {
            var start = new ProcessStartInfo(git) { WorkingDirectory = repo, UseShellExecute = false, CreateNoWindow = true,
                RedirectStandardOutput = true, RedirectStandardError = true, RedirectStandardInput = true,
                StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8 };
            start.ArgumentList.Add("-c"); start.ArgumentList.Add("core.quotepath=false");
            foreach (var argument in arguments) start.ArgumentList.Add(argument);
            start.Environment["GIT_TERMINAL_PROMPT"] = "0"; start.Environment["GCM_INTERACTIVE"] = "Never";
            start.Environment["GIT_SSH_COMMAND"] = "ssh -o BatchMode=yes";
            using var process = Process.Start(start) ?? throw new IOException("无法启动 Git");
            process.StandardInput.Close();
            var stdout = ReadBounded(process.StandardOutput); var stderr = ReadBounded(process.StandardError);
            try { await process.WaitForExitAsync(timeout.Token); }
            catch { try { process.Kill(true); } catch { } throw; }
            return (process.ExitCode, (await stdout) + (await stderr));
        }
        async Task<bool> Step(string label, params string[] args) {
            var value = await Command(args); log.AppendLine(label).AppendLine(value.Output);
            return value.Exit == 0;
        }
        try {
            var top = await Command("rev-parse", "--show-toplevel");
            if (top.Exit != 0 || !System.IO.Path.GetFullPath(top.Output.Trim()).Equals(repo, OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal))
                return new(false, top.Output, "所选 .git 不属于有效的工作目录仓库");
            switch (request.Action) {
                case "status":
                    if (!await Step("仓库状态", "status", "--short", "--branch")) return new(false, log.ToString(), "Git 状态查询失败");
                    break;
                case "commit-push":
                    if (string.IsNullOrWhiteSpace(request.Message) || request.Message.Length > 2000 || request.Message.Contains('\0')) throw new ArgumentException("请填写有效提交说明");
                    if (!await Step("暂存全部改动", "add", "-A", "--", ".")) return new(false, log.ToString(), "暂存失败，未提交或推送");
                    var diff = await Command("diff", "--cached", "--quiet", "--exit-code");
                    if (diff.Exit == 1) {
                        if (!await Step("创建提交", "commit", "-m", request.Message)) return new(false, log.ToString(), "提交失败，未推送；请检查 Git 用户身份或提交钩子");
                    } else if (diff.Exit != 0) return new(false, log.ToString(), "无法核对暂存区，未推送");
                    else log.AppendLine("没有新改动，直接推送已有提交。");
                    if (!await Step("推送", "push")) return new(false, log.ToString(), "推送失败；已有提交会保留。请检查远端、上游分支和电脑上的登录凭据。");
                    break;
                case "push":
                    if (!await Step("推送", "push")) return new(false, log.ToString(), "推送失败，请检查上游分支和电脑上的登录凭据");
                    break;
                case "pull":
                    if (!await Step("拉取（仅快进）", "pull", "--ff-only")) return new(false, log.ToString(), "拉取失败，请检查本地改动、分支分叉和登录凭据");
                    break;
                default: throw new ArgumentException("不支持此 Git 操作");
            }
            return new(true, log.ToString());
        } catch (Exception error) {
            return new(false, log.ToString(), error is OperationCanceledException ? "Git 操作超时，请核对提交及远端状态后再操作" : error.Message);
        }
    }
}
