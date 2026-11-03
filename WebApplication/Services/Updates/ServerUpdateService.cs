using System.Diagnostics;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using RemoteTool.Updates;

namespace RemoteTool.WebApi.Services.Updates;

public sealed class ServerUpdateService(IHttpClientFactory clients, IConfiguration config, IWebHostEnvironment environment,
    HermesManagement hermes, HermesProfiles profiles, HermesBridge hermesBridge, CodexBridge codexBridge, RunRegistry registry, UpdaterBootstrap bootstrap) : BackgroundService
{
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly object requests = new();
    private readonly System.Collections.Concurrent.ConcurrentDictionary<long, int> activeRequests = new(); // 启动时间刻度 -> 并发数
    private bool draining;
    // 长连接（事件流、终端 WS）会被客户端一直挂着，不代表有任务在跑；只有最近 45 秒内发起的请求才算“进行中”
    private static readonly TimeSpan RecentRequestWindow = TimeSpan.FromSeconds(45);
    private readonly string root = Path.Combine(RemoteToolPaths.ProgramData, "updates");
    private string FileAt(string name) => Path.Combine(root, name);
    public static long Sequence => long.TryParse(typeof(ServerUpdateService).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
        .FirstOrDefault(a => a.Key == "ReleaseSequence")?.Value, out var value) ? value : 0;
    public bool Enter(long stamp) { lock (requests) { if (draining || Job?.Phase is "pulling" or "building" or "switching" or "verifying" or "rolling_back" or "migrating" or "recovery_required") return false; activeRequests.AddOrUpdate(stamp, 1, (_, value) => value + 1); return true; } }
    public void Leave(long stamp) { lock (requests) { if (!activeRequests.TryGetValue(stamp, out var value)) return; if (value <= 1) activeRequests.TryRemove(stamp, out _); else activeRequests[stamp] = value - 1; } }
    private int RecentRequestCount() {
        var cutoff = DateTime.UtcNow.Ticks - RecentRequestWindow.Ticks;
        var count = 0;
        foreach (var pair in activeRequests) if (pair.Key >= cutoff) count += pair.Value;
        return count;
    }
    public void ReleaseDrain() { lock (requests) draining = false; }
    public Installation Installation => File.Exists(FileAt("installation.json")) ? UpdateContract.Read<Installation>(FileAt("installation.json")) : null;
    public bool Installed => Installation != null && (OperatingSystem.IsWindows() && Installation.Host == "iis" || OperatingSystem.IsLinux() && Installation.Host == "systemd") && (Installation.Mode == "source" || !DevelopmentTree());
    private bool DevelopmentTree() {
        for (var dir = new DirectoryInfo(environment.ContentRootPath); dir != null; dir = dir.Parent)
            if (Directory.Exists(Path.Combine(dir.FullName, ".git")) || File.Exists(Path.Combine(dir.FullName, ".git"))) return true;
        return false;
    }
    public UpdatePolicy Policy => File.Exists(FileAt("policy.json")) ? UpdateContract.Read<UpdatePolicy>(FileAt("policy.json")) : new();
    public UpdateJob Job => File.Exists(FileAt("job.json")) ? UpdateContract.Read<UpdateJob>(FileAt("job.json")) : null;
    public UpdateJob ReadJob(string id) => Guid.TryParseExact(id, "N", out _) && File.Exists(FileAt(Path.Combine("jobs", id, "status.json")))
        ? UpdateContract.Read<UpdateJob>(FileAt(Path.Combine("jobs", id, "status.json"))) : null;
    private bool BusyJob => Job?.Phase is "requested" or "downloading" or "waiting" or "pulling" or "building" or "switching" or "verifying" or "rolling_back" or "recovery_required" or "migrating";
    public object Status() {
        JsonNode check = null;
        if (File.Exists(FileAt("check.json"))) check = JsonNode.Parse(File.ReadAllText(FileAt("check.json")));
        return new { supported = OperatingSystem.IsWindows() || OperatingSystem.IsLinux(), installed = Installed, currentVersion = ServerBuild.Version, currentSequence = Sequence,
            policy = Policy, check, job = Job, mode = Installation?.Mode, canRebuild = Installed && Installation.Mode == "source",
            preparationMessage = bootstrap.Message,
            supportsForce = Installed && Installation.UpdaterVersion == UpdateContract.UpdaterVersion,
            supportsSourcePull = Installed && Installation.Mode == "source" && Installation.UpdaterVersion == UpdateContract.UpdaterVersion,
            message = Installed ? Installation.Mode == "source" ? "可拉取当前分支并更新，或仅编译当前磁盘源码" : null : bootstrap.Message };
    }
    public async Task<object> SavePolicy(UpdatePolicy policy, CancellationToken ct) {
        if (policy.Mode is not ("notify" or "download" or "automatic") || policy.StartHourUtc is < 0 or > 23 || policy.EndHourUtc is < 0 or > 23)
            throw new InvalidOperationException("更新设置无效");
        if (policy.Mode != "notify" && !Installed) throw new InvalidOperationException("请先安装独立更新器");
        await gate.WaitAsync(ct);
        try { UpdateContract.Write(FileAt("policy.json"), policy); return Status(); } finally { gate.Release(); }
    }
    public async Task<object> Check(CancellationToken ct) {
        await bootstrap.Ensure(ct);
        if (!OperatingSystem.IsWindows() && !OperatingSystem.IsLinux()) return Status();
        await bootstrap.Resume(ct);
        await gate.WaitAsync(ct);
        try {
            if (Installation?.Mode == "source") {
                try { UpdateContract.Write(FileAt("check.json"),await new SourceRepository(Installation.ProjectDirectory!).Check(ServerBuild.Version)); }
                catch(Exception error) when(error is not OperationCanceledException || !ct.IsCancellationRequested) {
                    UpdateContract.Write(FileAt("check.json"),new {kind="source",state="failed",checkedAt=DateTimeOffset.UtcNow,available=false,error=error.Message});
                }
                return Status();
            }
            using var client = clients.CreateClient(); client.Timeout = TimeSpan.FromSeconds(30);
            client.DefaultRequestHeaders.UserAgent.ParseAdd("RemoteTool-ServerUpdater/1.0");
            using var request = new HttpRequestMessage(HttpMethod.Get, $"https://api.github.com/repos/{UpdateContract.Repository}/releases?per_page=100");
            if (File.Exists(FileAt("etag.txt"))) request.Headers.TryAddWithoutValidation("If-None-Match", File.ReadAllText(FileAt("etag.txt")));
            using var response = await client.SendAsync(request, ct);
            ServerRelease release;
            if (response.StatusCode == System.Net.HttpStatusCode.NotModified && File.Exists(FileAt("manifest.json")))
                release = UpdateContract.Verify(await File.ReadAllBytesAsync(FileAt("manifest.json"), ct));
            else {
                response.EnsureSuccessStatusCode();
                var releases = JsonNode.Parse(await response.Content.ReadAsStringAsync(ct))!.AsArray();
                var candidate = releases.Where(r => r?["draft"]?.GetValue<bool>() == false && r?["prerelease"]?.GetValue<bool>() == false &&
                    r?["tag_name"]?.ToString().StartsWith("server-v", StringComparison.Ordinal) == true)
                    .OrderByDescending(r => r!["published_at"]!.ToString(), StringComparer.Ordinal).FirstOrDefault()
                    ?? throw new InvalidOperationException("尚无正式服务端发行版本");
                string Asset(string name) => candidate["assets"]!.AsArray().First(a => a!["name"]!.ToString() == name)!["browser_download_url"]!.ToString();
                async Task<byte[]> Download(string url) {
                    var uri = new Uri(url);
                    if (uri.Scheme != "https" || uri.Host != "github.com" || !uri.AbsolutePath.StartsWith("/" + UpdateContract.Repository + "/releases/download/server-v", StringComparison.Ordinal))
                        throw new InvalidDataException("清单下载地址无效");
                    using var result = await client.GetAsync(uri, ct); result.EnsureSuccessStatusCode();
                    var bytes = await result.Content.ReadAsByteArrayAsync(ct);
                    if (bytes.Length > 262144) throw new InvalidDataException("清单大小超限");
                    return bytes;
                }
                var manifest = await Download(Asset("server-update.json"));
                release = UpdateContract.Verify(manifest);
                Directory.CreateDirectory(root);
                await File.WriteAllBytesAsync(FileAt("manifest.json"), manifest, ct);
                if (response.Headers.ETag != null) await File.WriteAllTextAsync(FileAt("etag.txt"), response.Headers.ETag.ToString(), ct);
            }
            UpdateContract.Write(FileAt("check.json"), new { checkedAt = DateTimeOffset.UtcNow, available = release.ReleaseSequence > Sequence, release.Version,
                release.ReleaseSequence, release.ReleaseNotes, error = (string)null });
        } catch (Exception error) when (error is not OperationCanceledException || !ct.IsCancellationRequested) {
            UpdateContract.Write(FileAt("check.json"), new { checkedAt = DateTimeOffset.UtcNow, available = false, error = error.Message });
        } finally { gate.Release(); }
        return Status();
    }
    public async Task<object> Install(long sequence, CancellationToken ct, bool downloadOnly = false, bool rebuild = false, bool force = false, bool pullSource = false) {
        await gate.WaitAsync(ct);
        try {
            if (!Installed) throw new InvalidOperationException("源码运行实例不能自动覆盖，请先迁移到正式安装目录");
            if (BusyJob) throw new InvalidOperationException("已有更新任务，请先核对其结果");
            if (force && Installation.UpdaterVersion != UpdateContract.UpdaterVersion) throw new InvalidOperationException("请先点立即检查以升级独立更新器，再使用强制中断");
            if (rebuild && Installation.Mode != "source") throw new InvalidOperationException("当前实例未配置开发重编译模式");
            if(pullSource && (!rebuild || Installation.Mode!="source" || Installation.UpdaterVersion!=UpdateContract.UpdaterVersion))throw new InvalidOperationException("当前更新器尚不支持拉取源码，请先检查更新");
            var source=pullSource?await new SourceRepository(Installation.ProjectDirectory!).Read():null;
            if(source?.Dirty==true)throw new InvalidOperationException("本地有未提交改动，请先处理或选择编译当前源码");
            var release = rebuild ? null : UpdateContract.Verify(await File.ReadAllBytesAsync(FileAt("manifest.json"), ct));
            if (!rebuild && (release.ReleaseSequence != sequence || sequence <= Sequence)) throw new InvalidOperationException("更新版本已变化，请重新检查");
            var job = new UpdateJob(Guid.NewGuid().ToString("N"), "requested", downloadOnly ? "预下载" : pullSource ? "等待空闲后拉取并更新源码" : rebuild ? "等待空闲后重编译当前源码" : "等待独立更新器", rebuild ? Sequence : sequence,
                rebuild ? "本地源码" : release.Version, rebuild || Installation.Mode == "source" && !downloadOnly, !rebuild && Installation.Mode == "source" && !downloadOnly, force, pullSource, source?.Branch);
            var snapshot = FileAt(Path.Combine("jobs", job.Id));
            Directory.CreateDirectory(snapshot);
            if (!rebuild) {
                File.Copy(FileAt("manifest.json"), Path.Combine(snapshot, "manifest.json"));
            }
            UpdateContract.Write(FileAt("request.json"), new { job.Id, downloadOnly });
            UpdateContract.WriteJob(root, job);
            try { await bootstrap.Start(job.Id, CancellationToken.None); }
            catch (Exception error) when (error is System.ComponentModel.Win32Exception or IOException) {
                UpdateContract.WriteJob(root, job with { Phase = "failed", Message = "无法启动更新进程：" + error.Message }); throw new IOException("无法启动更新进程；网站未停止", error);
            }
            return Status();
        } finally { gate.Release(); }
    }
    /// <summary>用户在“等待空闲”阶段取消：写一个标记文件，更新器每 5 秒检查一次。</summary>
    public bool RequestCancel() {
        var job = Job;
        if (job == null || job.Phase is not ("requested" or "waiting" or "pulling")) return false;
        UpdateContract.Write(FileAt("cancel.json"), new { id = job.Id, at = DateTimeOffset.UtcNow });
        return true;
    }
    public bool AuthenticateWorker(string token) {
        var expected = Installation?.WorkerToken;
        return expected != null && CryptographicOperations.FixedTimeEquals(System.Text.Encoding.UTF8.GetBytes(token), System.Text.Encoding.UTF8.GetBytes(expected));
    }
    public async Task<object> Readiness(string id, CancellationToken ct) {
        if (Job?.Id != id || !BusyJob) throw new InvalidOperationException("更新任务标识无效");
        // 只有在确认可以更新时才进入 draining；否则检查期间会把正常请求打成 503
        int count; lock (requests) { count = RecentRequestCount(); }
        try {
        var blockers = new List<string>();
        var notices = new List<string>();
        // Force is an explicit per-job choice. It is never stored in automatic policy.
        if (Job.Force) { lock (requests) draining = true; return new { ready = true, blockers, notices = Array.Empty<string>() }; }
        if (count > 0) blockers.Add($"有 {count} 个请求或实时连接尚未结束");
        var hermesIdle = false;
        // Native tasks can outlive their HTTP connection. Missing evidence never means idle.
        if (Directory.Exists(profiles.Root)) {
            try {
                using (HermesProfileScope.Enter("default")) {
                    var value = await hermes.Invoke("admin_gateway_status", null, ct);
                    hermesIdle = value.TryGetProperty("running", out var running) && running.GetBoolean() &&
                        value.GetProperty("fresh").GetBoolean() && value.GetProperty("active").GetInt32() == 0;
                    if (!value.GetProperty("fresh").GetBoolean() || value.GetProperty("active").GetInt32() != 0) blockers.Add("Hermes 状态未确认或仍有任务");
                }
            } catch { blockers.Add("无法确认 Hermes 空闲"); }
        }
        var checkedRuns = new HashSet<(string Agent, string Run)>();
        async Task CheckRun(string agent, string session, string run, bool staleTracking = false) {
            if (!checkedRuns.Add((agent, run))) return;
            try {
                HttpResponseMessage response;
                if (agent.StartsWith("hermes", StringComparison.Ordinal)) {
                    using (HermesProfileScope.Enter(agent.StartsWith("hermes@", StringComparison.Ordinal) ? agent[7..] : "default"))
                        response = await hermesBridge.SendAsync(HttpMethod.Get, "v1/runs/" + Uri.EscapeDataString(run), null, null, ct);
                } else response = await codexBridge.SendAsync(HttpMethod.Get, "runs/" + Uri.EscapeDataString(run), null, ct);
                using (response) {
                    var value = response.IsSuccessStatusCode ? JsonNode.Parse(await response.Content.ReadAsStringAsync(ct)) : null;
                    if (value?["status"]?.ToString() is "completed" or "failed" or "cancelled" or "interrupted") registry.Clear(agent, session, run);
                    else if (UpdateContract.ReviewedHistoricalRun(value)) { /* Keep original receipt and idempotency key. */ }
                    else if (agent.StartsWith("hermes", StringComparison.Ordinal) && hermesIdle && response.StatusCode == System.Net.HttpStatusCode.NotFound &&
                        JsonNode.Parse(await response.Content.ReadAsStringAsync(ct))?["error"]?["code"]?.ToString() == "run_not_found")
                        registry.Clear(agent, session, run);
                    // Age, a missing receipt, and a failed probe do not prove execution ended.
                    // In particular, bridge shutdown also fences acceptance_unknown records.
                    else if (!response.IsSuccessStatusCode) blockers.Add($"无法确认智能体任务状态：{agent}（HTTP {(int)response.StatusCode}）");
                    else if (value?["status"]?.ToString() == "acceptance_unknown") {
                        // 登记很久没更新、只剩受理确认的记录不可能仍在执行：放行并提示，避免永远挡住更新
                        if (staleTracking || UpdateContract.StaleAcceptance(value)) notices.Add("有历史任务结果未核对（超过 6 小时，已跳过）");
                        else blockers.Add("智能体任务结果待核对：" + agent);
                    }
                    else blockers.Add("智能体运行中或结果待核对：" + agent);
                }
            } catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch { blockers.Add("无法确认智能体任务状态：" + agent); }
        }
        // 先读 codex 原生记录：判断“历史任务”要看记录自身的时间戳，登记表只用于找到 run
        var nativeRuns = Path.Combine(RemoteToolPaths.CodexState, "runs.json");
        var native = File.Exists(nativeRuns) ? JsonNode.Parse(await File.ReadAllTextAsync(nativeRuns, ct)) as JsonObject : null;
        var registryPath = RemoteToolPaths.RunRegistry;
        if (File.Exists(registryPath) && JsonNode.Parse(await File.ReadAllTextAsync(registryPath, ct)) is JsonObject runs)
            foreach (var pair in runs) {
                var separator = pair.Key.IndexOf(':');
                if (separator < 0) { blockers.Add("运行记录格式待核对"); continue; }
                var runId = pair.Value?["run_id"]?.ToString() ?? "";
                var staleTracking = native?[runId] is JsonNode record && UpdateContract.StaleAcceptance(record);
                await CheckRun(pair.Key[..separator], pair.Key[(separator + 1)..], runId, staleTracking);
            }
        if (native != null)
            foreach (var pair in native.Where(p => UpdateContract.RunBlocksMaintenance(p.Value))) {
                // 旧记录只丢了受理确认、又很久没有动静：不可能还在执行，放行并在提示里说明
                if (UpdateContract.StaleAcceptance(pair.Value)) { notices.Add("有历史任务结果未核对（超过 6 小时，已跳过）"); continue; }
                await CheckRun("codex", pair.Value?["session_id"]?.ToString() ?? "", pair.Key);
            }
        try { if (Hangfire.JobStorage.Current.GetMonitoringApi().ProcessingCount() > 0) blockers.Add("后台定时任务正在执行"); }
        catch { blockers.Add("无法确认后台任务空闲"); }
        // 同一类阻塞只显示一次并带数量，避免界面刷出一长串重复原因
        var summarized = blockers.GroupBy(b => b).Select(g => g.Count() > 1 ? $"{g.Key} ×{g.Count()}" : g.Key).ToList();
        if (summarized.Count == 0) { lock (requests) draining = true; }
        return new { ready = summarized.Count == 0, blockers = summarized, notices = notices.GroupBy(n => n).Select(g => g.Count() > 1 ? $"{g.Key} ×{g.Count()}" : g.Key).ToList() };
        } catch { ReleaseDrain(); throw; }
    }
    protected override async Task ExecuteAsync(CancellationToken stoppingToken) {
        while (!stoppingToken.IsCancellationRequested) {
            try {
                await Check(stoppingToken);
                if (Installed && Installation.Mode != "source" && !BusyJob && Policy.Mode != "notify" && File.Exists(FileAt("check.json")) &&
                    JsonNode.Parse(File.ReadAllText(FileAt("check.json")))?["available"]?.GetValue<bool>() == true &&
                    (Policy.Mode == "download" || UpdateContract.InWindow(Policy, DateTime.UtcNow.Hour))) {
                    var release = UpdateContract.Read<ServerRelease>(FileAt("manifest.json"));
                    if (Job?.Sequence != release.ReleaseSequence || Job.Phase is "deferred" || Job.Phase == "downloaded" && Policy.Mode == "automatic")
                        await Install(release.ReleaseSequence, stoppingToken, Policy.Mode == "download");
                }
            } catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error) { UpdateContract.Write(FileAt("background-error.json"), new { error = error.Message, at = DateTimeOffset.UtcNow }); }
            await Task.Delay(TimeSpan.FromMinutes(30) + TimeSpan.FromSeconds(Random.Shared.Next(120)), stoppingToken);
        }
    }
}
