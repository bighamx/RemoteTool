using System.Diagnostics;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Text.Json.Nodes;
using RemoteTool.Updates;

namespace RemoteTool.WebApi.Services.Updates;

public interface ISourceUpdateRuntime : IUpdateRuntime
{
    Task StopBridge(bool force);
    Task WarmBridge();
    Task Compile(string project, string output, long sequence);
    Task<string> Git(string directory, params string[] arguments);
}

public sealed partial class Worker
{
    static readonly HashSet<string> CodeExtensions = new(StringComparer.OrdinalIgnoreCase) {
        ".dll", ".so", ".dylib", ".pdb", ".exe", ".json", ".config", ".py", ".md", ".cshtml", ".js", ".css", ".html", ".svg", ".png", ".woff", ".woff2"
    };
    static bool ConfigurationFile(string path) => Path.GetFileName(path).StartsWith("appsettings", StringComparison.OrdinalIgnoreCase) || Path.GetFileName(path).Equals("web.config", StringComparison.OrdinalIgnoreCase);
    static string[] RuntimeFiles(string directory) => Directory.EnumerateFiles(directory, "*", new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint })
        .Where(p => CodeExtensions.Contains(Path.GetExtension(p)) && !Path.GetRelativePath(directory, p).Split(Path.DirectorySeparatorChar)
            .Any(s => new[] { "logs", "uploads", "data", "attachments" }.Contains(s, StringComparer.OrdinalIgnoreCase))).ToArray();
    static string BuildId(string path) {
        using var file = File.OpenRead(path); using var pe = new PEReader(file);
        var reader = pe.GetMetadataReader(); return reader.GetGuid(reader.GetModuleDefinition().Mvid).ToString("N");
    }
    async Task<string> Tool(string executable, string directory, params string[] args) {
        var start = new ProcessStartInfo(executable) { WorkingDirectory = directory, UseShellExecute = false, CreateNoWindow = true,
            RedirectStandardOutput = true, RedirectStandardError = true };
        if (executable == "git") {
            start.ArgumentList.Add("-c");
            start.ArgumentList.Add("safe.directory=" + Directory.GetParent(directory)!.FullName);
        }
        foreach (var arg in args) start.ArgumentList.Add(arg);
        using var process = Process.Start(start)!;
        if (executable == "dotnet" && File.Exists(At("transaction.json"))) {
            var transaction = UpdateContract.Read<JsonObject>(At("transaction.json"));
            transaction["buildPid"] = process.Id; transaction["buildStartedTicks"] = process.StartTime.ToUniversalTime().Ticks;
            UpdateContract.Write(At("transaction.json"), transaction);
        }
        var stdout = process.StandardOutput.ReadToEndAsync(); var stderr = process.StandardError.ReadToEndAsync();
        using var deadline = new CancellationTokenSource(TimeSpan.FromMinutes(15));
        try { await process.WaitForExitAsync(deadline.Token); }
        catch { if (!process.HasExited) process.Kill(true); throw; }
        var text = await stdout; var errors = await stderr;
        if (executable == "dotnet") await File.WriteAllTextAsync(At(Path.Combine("jobs", job.Id, "build.log")), text + errors);
        if (process.ExitCode != 0) throw new IOException(executable == "dotnet" ? "编译失败，请查看本机更新任务 build.log" : "无法核对 Git 工作树");
        return text.Trim();
    }
    async Task StopBridge() {
        if (runtime is ISourceUpdateRuntime source) { await source.StopBridge(job.Force); return; }
        var folder = Path.Combine(Path.GetDirectoryName(root)!, "codex-bridge");
        var statusFile = Path.Combine(folder, "status.json"); var connectionFile = Path.Combine(folder, "connection.json");
        if (!File.Exists(statusFile) || !File.Exists(connectionFile)) return;
        var status = UpdateContract.Read<JsonObject>(statusFile); var connection = UpdateContract.Read<JsonObject>(connectionFile);
        var pid = status["pid"]!.GetValue<int>();
        Process process;
        try { process = Process.GetProcessById(pid); } catch (ArgumentException) { return; }
        using (process) {
            var endpoint = "http://127.0.0.1:" + status["port"]!.ToString();
            using var probe = new HttpRequestMessage(HttpMethod.Get, endpoint + "/health");
            probe.Headers.Authorization = new("Bearer", connection["token"]!.ToString());
            using var response = await http.SendAsync(probe); response.EnsureSuccessStatusCode();
            var health = JsonNode.Parse(await response.Content.ReadAsStringAsync())!;
            // Linux .NET derives wall-clock start time from a per-process boot-clock
            // sample. Small sub-second differences are expected across processes.
            var started=health["process_started_ticks"]?.GetValue<long>() ?? 0;
            var delta=Math.Abs(started-process.StartTime.ToUniversalTime().Ticks);
            if (health["maintenance_shutdown"]?.GetValue<bool>() != true || health["bridge_pid"]?.GetValue<int>() != pid ||
                started <= 0 || delta > (OperatingSystem.IsLinux()?TimeSpan.TicksPerSecond:0))
                throw new IOException("当前 bridge 不支持安全关闭，请先部署支持维护接口的版本；不会按 PID 强杀");
            if (job.Force) {
                // Explicitly authorized for this job only. The authenticated endpoint,
                // PID and start time above identify our bridge, never desktop Codex.
                process.Kill(entireProcessTree: true);
                using var forcedTimeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
                await process.WaitForExitAsync(forcedTimeout.Token);
                return;
            }
            using var request = new HttpRequestMessage(HttpMethod.Post, endpoint + "/maintenance/shutdown");
            request.Headers.Authorization = new("Bearer", connection["token"]!.ToString());
            using var stopped = await http.SendAsync(request);
            if (!stopped.IsSuccessStatusCode) throw new IOException($"Codex bridge 尚未确认可安全关闭（HTTP {(int)stopped.StatusCode}），已取消编译；没有强制停止任务");
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
            await process.WaitForExitAsync(timeout.Token);
        }
    }
    async Task WarmBridge() {
        if (runtime is ISourceUpdateRuntime source) { await source.WarmBridge(); return; }
        using var request = new HttpRequestMessage(HttpMethod.Post, new Uri(config.HealthUrl).GetLeftPart(UriPartial.Authority) + "/api/server-update/warm-bridge");
        request.Headers.Add("X-RemoteTool-Updater", config.WorkerToken);
        using var response = await http.SendAsync(request); response.EnsureSuccessStatusCode();
        var state = JsonNode.Parse(await response.Content.ReadAsStringAsync());
        if (state?["ready"]?.GetValue<bool>() != true) throw new IOException("新 Codex bridge 未就绪");
    }
    async Task RebuildSource() {
        if (config.Mode != "source" || config.ProjectDirectory == null || config.RuntimeDirectory == null) throw new IOException("未配置开发重编译目录");
        var project = Path.GetFullPath(config.ProjectDirectory);
        var output = Path.GetFullPath(config.RuntimeDirectory);
        if (!File.Exists(Path.Combine(project, "WebApplication.csproj")) || !(await CurrentDirectory()).Equals(output, StringComparison.OrdinalIgnoreCase))
            throw new IOException("IIS 目录或源码项目与安装配置不符");
        if (job.ReleaseSource) {
            var release = UpdateContract.Verify(await File.ReadAllBytesAsync(At(Path.Combine("jobs", job.Id, "manifest.json"))));
            async Task<string> Git(params string[] args) => runtime is ISourceUpdateRuntime source ? await source.Git(project, args) : await Tool("git", project, args);
            if (release.ReleaseSequence != job.Sequence || await Git("rev-parse", "HEAD") != release.Commit || (await Git("status", "--porcelain")).Length != 0)
                throw new IOException("开发模式请先把源码同步到发行提交并保持工作树干净；更新器不会拉取、切分支或重置 Git");
        }
        await Readiness();
        var oldHealth = await Health();
        if (oldHealth["id"]?.ToString() != config.DeviceId) throw new IOException("设备身份已变化");
        if(job.PullSource) {
            Phase("pulling","正在拉取当前分支的最新源码");
            var repository=new SourceRepository(project,runtime is ISourceUpdateRuntime source?args=>source.Git(project,args):null);
            var before=await repository.Read();
            var after=await repository.Pull(job.SourceBranch ?? throw new IOException("未记录更新分支，请重新提交更新"));
            UpdateContract.Write(At(Path.Combine("jobs",job.Id,"source.json")),new {branch=after.Branch,before=before.Revision,after=after.Revision});
        }
        var backup = At(Path.Combine("jobs", job.Id, "runtime-backup")); Directory.CreateDirectory(backup);
        var files = RuntimeFiles(output);
        foreach (var file in files) {
            var destination = Path.Combine(backup, Path.GetRelativePath(output, file));
            Directory.CreateDirectory(Path.GetDirectoryName(destination)!); File.Copy(file, destination, true);
        }
        // A final atomic bridge check can still refuse after readiness (e.g. a direct
        // bridge client started work). Keep IIS available when that happens.
        await StopBridge();
        var transaction = new JsonObject { ["kind"] = "source", ["previous"] = output, ["target"] = output, ["sequence"] = oldHealth["sequence"]!.DeepClone(),
            ["buildId"] = oldHealth["buildId"]!.DeepClone(), ["backup"] = backup, ["buildStarted"] = false };
        UpdateContract.Write(At("transaction.json"), transaction);
        Phase("building", job.Force ? "已强制中断 Codex 任务，正在编译并重启" : "正在编译并重启");
        await Command("stop", "apppool", "/apppool.name:" + config.Pool);
        transaction["buildStarted"] = true; UpdateContract.Write(At("transaction.json"), transaction);
        if (runtime is ISourceUpdateRuntime sourceRuntime) await sourceRuntime.Compile(project, output, job.Sequence);
        else await Tool("dotnet", project, "build", "WebApplication.csproj", "-c", "Debug", "-o", output, "--disable-build-servers", "-p:UseSharedCompilation=false", "-p:ServerReleaseSequence=" + job.Sequence);
        // Build may copy source appsettings; retain the existing deployment's configuration.
        foreach (var file in files.Where(ConfigurationFile)) File.Copy(Path.Combine(backup, Path.GetRelativePath(output, file)), file, true);
        var expected = BuildId(Path.Combine(output, "WebApplication.dll"));
        await Command("start", "apppool", "/apppool.name:" + config.Pool);
        Phase("verifying", "核对重编译程序集与新 bridge"); await Verify(job.Sequence, expected); await WarmBridge();
        Phase("completed", "源码重新编译完成，Web 与 Codex bridge 已恢复"); File.Delete(At("transaction.json"));
    }
    async Task Recover(JsonObject transaction) {
        if (transaction["kind"]?.ToString() != "source") { await Rollback(transaction); return; }
        if (!(await CurrentDirectory()).Equals(transaction["previous"]!.ToString(), StringComparison.OrdinalIgnoreCase)) throw new IOException("IIS 目录已变更，拒绝覆盖人工处理");
        Phase("rolling_back", "编译未确认，恢复原运行程序");
        if (transaction["buildPid"] is JsonNode buildPid) {
            Process? build = null;
            try { build = Process.GetProcessById(buildPid.GetValue<int>()); } catch (ArgumentException) { }
            using (build) {
                if (build != null && build.StartTime.ToUniversalTime().Ticks == transaction["buildStartedTicks"]?.GetValue<long>()) {
                    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(60));
                    await build.WaitForExitAsync(timeout.Token);
                }
            }
        }
        try { await Command("stop", "apppool", "/apppool.name:" + config.Pool); } catch { /* May already be stopped after a failed build; health verification remains mandatory. */ }
        if (transaction["buildStarted"]?.GetValue<bool>() == true) await StopBridge();
        var output = transaction["previous"]!.ToString(); var backup = transaction["backup"]!.ToString();
        foreach (var file in Directory.GetFiles(backup, "*", SearchOption.AllDirectories)) {
            var target = Path.Combine(output, Path.GetRelativePath(backup, file)); Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            if (File.Exists(target) && !ConfigurationFile(target)) File.Move(target, target + ".before-rollback-" + Guid.NewGuid().ToString("N"));
            File.Copy(file, target, true);
        }
        await Command("start", "apppool", "/apppool.name:" + config.Pool);
        await Verify(transaction["sequence"]!.GetValue<long>(), transaction["buildId"]!.ToString()); await WarmBridge();
        Phase("rolled_back", job.PullSource ? "编译或启动失败，已恢复原程序；已拉取源码保留，可修复后重新编译，用户数据未修改" : "更新或启动失败，已恢复原程序；源码与用户数据未修改"); File.Delete(At("transaction.json"));
    }
}
