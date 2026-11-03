using System.Diagnostics;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using RemoteTool.Updates;

namespace RemoteTool.WebApi.Services.Updates;

public interface IUpdateRuntime
{
    DateTime UtcNow { get; }
    Task Delay(int milliseconds);
    Task Execute(string[] arguments);
    Task<string> Directory();
    Task<JsonObject> Health();
    Task<JsonNode> Readiness(string jobId);
}

public sealed partial class Worker(string configPath, IUpdateRuntime? runtime = null)
{
    readonly string root = Path.GetDirectoryName(configPath)!;
    string At(string name) => Path.Combine(root, name);
    Installation config = null!;
    UpdateJob job = null!;
    readonly HttpClient http = new() { Timeout = TimeSpan.FromSeconds(30) };
    void Phase(string phase, string message) { job = job with { Phase = phase, Message = message }; UpdateContract.WriteJob(root, job); }
    async Task Command(params string[] arguments) {
        if (runtime != null) { await runtime.Execute(arguments); return; }
        if (config.Host == "systemd") { await Systemd(arguments[0], config.Pool); return; }
        var appcmd = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "System32", "inetsrv", "appcmd.exe");
        var start = new ProcessStartInfo(appcmd) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var value in arguments) start.ArgumentList.Add(value);
        using var process = Process.Start(start)!;
        var stdout = process.StandardOutput.ReadToEndAsync(); var stderr = process.StandardError.ReadToEndAsync();
        using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(60));
        try { await process.WaitForExitAsync(deadline.Token); }
        catch { if (!process.HasExited) process.Kill(); throw; }
        await stdout; await stderr;
        if (process.ExitCode != 0) throw new InvalidOperationException("IIS 操作失败：" + arguments[0]);
    }
    async Task<string> CurrentDirectory() {
        if (runtime != null) return await runtime.Directory();
        if (config.Host == "systemd") {
            var directory = await Systemd("show", config.Pool, "--property=WorkingDirectory", "--value");
            if (Path.GetFullPath(directory) != config.RuntimeDirectory) throw new IOException("systemd 工作目录已变化，拒绝更新其他服务");
            return Path.GetFullPath(directory);
        }
        var appcmd = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "System32", "inetsrv", "appcmd.exe");
        var start = new ProcessStartInfo(appcmd) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true };
        foreach (var value in new[] { "list", "vdir", config.Site + "/", "/text:physicalPath" }) start.ArgumentList.Add(value);
        using var process = Process.Start(start)!;
        var valueText = await process.StandardOutput.ReadToEndAsync(); await process.WaitForExitAsync();
        if (process.ExitCode != 0) throw new InvalidOperationException("无法读取 IIS 网站目录");
        return Path.GetFullPath(Environment.ExpandEnvironmentVariables(valueText.Trim()));
    }
    async Task<JsonObject> Health() => runtime != null ? await runtime.Health() : (JsonNode.Parse(await http.GetStringAsync(config.HealthUrl)) ?? throw new IOException("健康检查为空")).AsObject();
    DateTime Now => runtime?.UtcNow ?? DateTime.UtcNow;
    Task Delay(int milliseconds) => runtime?.Delay(milliseconds) ?? Task.Delay(milliseconds);
    async Task Verify(long sequence, string? buildId = null) {
        var deadline = Now.AddSeconds(90);
        while (Now < deadline) {
            try { var value = await Health(); if (value["id"]?.ToString() == config.DeviceId && value["sequence"]?.GetValue<long>() == sequence &&
                (buildId == null || value["buildId"]?.ToString() == buildId)) return; }
            catch (Exception error) when (error is HttpRequestException or TaskCanceledException or IOException) { }
            await Delay(2000);
        }
        throw new IOException("未确认目标版本及设备身份恢复");
    }
    async Task Download(ServerPackage package, string destination) {
        if (File.Exists(destination) && await Valid(destination, package)) return;
        using var response = await http.GetAsync(package.Url, HttpCompletionOption.ResponseHeadersRead); response.EnsureSuccessStatusCode();
        var temporary = destination + ".partial";
        try {
            await using (var source = await response.Content.ReadAsStreamAsync()) {
                await using var target = new FileStream(temporary, FileMode.Create, FileAccess.Write);
                var buffer = new byte[81920]; long total = 0; int length;
                while ((length = await source.ReadAsync(buffer)) > 0) {
                    total += length; if (total > package.Size) throw new IOException("下载大小超过清单");
                    await target.WriteAsync(buffer.AsMemory(0, length));
                }
            }
            if (!await Valid(temporary, package)) throw new IOException("发行包大小或 SHA256 不匹配");
            File.Move(temporary, destination, true);
        } finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    static async Task<bool> Valid(string path, ServerPackage package) {
        await using var stream = File.OpenRead(path);
        return stream.Length == package.Size && Convert.ToHexString(await SHA256.HashDataAsync(stream)).Equals(package.Sha256, StringComparison.OrdinalIgnoreCase);
    }
    async Task Readiness() {
        if (runtime == null && config.Host == "systemd") await SystemdControl.VerifyControl(config.UserService,config.Pool);
        Phase("waiting", "等待全部任务、连接空闲");
        var deadline = Now.AddMinutes(30);
        var endpoint = new Uri(config.HealthUrl);
        var url = endpoint.GetLeftPart(UriPartial.Authority) + "/api/server-update/readiness?jobId=" + job.Id;
        while (Now < deadline) {
            if (File.Exists(At("cancel.json")) && UpdateContract.Read<JsonObject>(At("cancel.json"))?["id"]?.ToString() == job.Id) {
                File.Delete(At("cancel.json"));
                throw new UpdateCancelled();
            }
            JsonNode? state;
            if (runtime != null) state = await runtime.Readiness(job.Id);
            else {
                using var request = new HttpRequestMessage(HttpMethod.Post, url);
                request.Headers.Add("X-RemoteTool-Updater", config.WorkerToken);
                using var result = await http.SendAsync(request); result.EnsureSuccessStatusCode();
                state = JsonNode.Parse(await result.Content.ReadAsStringAsync());
            }
            if (state?["ready"]?.GetValue<bool>() == true) return;
            Phase("waiting", "等待空闲：" + string.Join("；", state?["blockers"]?.AsArray().Select(b => b?.ToString()) ?? []));
            await Delay(5000);
        }
        throw new IOException("等待空闲超时，已延后更新；未强制终止任务");
    }
    async Task Rollback(JsonObject transaction) {
        var current = await CurrentDirectory();
        if (!current.Equals(transaction["target"]!.ToString(), StringComparison.OrdinalIgnoreCase) &&
            !current.Equals(transaction["previous"]!.ToString(), StringComparison.OrdinalIgnoreCase))
            throw new IOException("IIS 目录已被其他管理员修改，不能自动覆盖");
        Phase("rolling_back", "恢复上一版本");
        await Command("set", "vdir", config.Site + "/", "/physicalPath:" + transaction["previous"]!.ToString());
        try { await Command("start", "apppool", "/apppool.name:" + config.Pool); } catch { await Command("recycle", "apppool", "/apppool.name:" + config.Pool); }
        await Verify(transaction["sequence"]!.GetValue<long>());
        Phase("rolled_back", "更新失败，已恢复上一版本");
        File.Delete(At("transaction.json"));
    }
    public async Task Run() {
        config = UpdateContract.Read<Installation>(configPath);
        FileStream workerLock;
        try { workerLock = new FileStream(At("worker.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException) { return; }
        using var heldLock = workerLock;
        var releaseDrain = false;
        try {
            if (!File.Exists(At("job.json"))) return;
            job = UpdateContract.Read<UpdateJob>(At("job.json"));
            using (var process = Process.GetCurrentProcess()) UpdateContract.Write(At(Path.Combine("jobs", job.Id, "worker.json")),
                new { pid = process.Id, startCookie = UpdaterProcess.StartCookie(process) });
            if (File.Exists(At("transaction.json"))) { await Recover(UpdateContract.Read<JsonObject>(At("transaction.json"))); return; }
            if (job.Phase is not ("requested" or "downloading" or "waiting" or "pulling")) return;
            releaseDrain = true;
            if (job.SourceBuild) { await RebuildSource(); return; }
            var manifest = await File.ReadAllBytesAsync(At(Path.Combine("jobs", job.Id, "manifest.json")));
            var release = UpdateContract.Verify(manifest);
            if (job.Sequence != release.ReleaseSequence) throw new IOException("清单与已接受任务不符");
            var current = await Health();
            var currentSequence = current["sequence"]!.GetValue<long>();
            if (current["id"]?.ToString() != config.DeviceId || currentSequence >= job.Sequence) throw new IOException("目标设备或版本已变化");
            var archive = At("package-" + job.Sequence + ".zip");
            Phase("downloading", "下载并校验服务端发行包"); await Download(release.Packages[UpdateContract.Platform], archive);
            if (UpdateContract.Read<JsonObject>(At("request.json"))["downloadOnly"]?.GetValue<bool>() == true) { Phase("downloaded", "已下载，等待安装"); return; }
            var version = Path.Combine(config.Releases, job.Sequence + "-" + job.Id + "-" + Guid.NewGuid().ToString("N"));
            Directory.CreateDirectory(version); UpdateContract.Extract(archive, version);
            if (config.Host == "systemd") { await InstallLinuxRelease(version); return; }
            File.Copy(Path.Combine(config.Shared, "web.config"), Path.Combine(version, "web.config"));
            await Readiness();
            var previous = await CurrentDirectory();
            for (var dir = new DirectoryInfo(previous); dir != null; dir = dir.Parent)
                if (File.Exists(Path.Combine(dir.FullName, ".git")) || Directory.Exists(Path.Combine(dir.FullName, ".git"))) throw new IOException("拒绝覆盖开发工作区");
            if (job.Force) await StopBridge();
            var transaction = new JsonObject { ["previous"] = previous, ["sequence"] = currentSequence, ["target"] = version };
            UpdateContract.Write(At("transaction.json"), transaction);
            Phase("switching", "切换版本并重启 Web 服务");
            await Command("stop", "apppool", "/apppool.name:" + config.Pool);
            await Command("set", "vdir", config.Site + "/", "/physicalPath:" + version);
            await Command("start", "apppool", "/apppool.name:" + config.Pool);
            Phase("verifying", "核对版本、身份和健康状态"); await Verify(job.Sequence);
            if (job.Force) await WarmBridge();
            Phase("completed", "服务端更新完成"); File.Delete(At("transaction.json"));
        } catch (UpdateCancelled) {
            Phase("cancelled", "已取消等待，本次未更新");
        } catch (Exception error) {
            if (job == null) throw;
            UpdateContract.Write(At(Path.Combine("jobs", job.Id, "failure.json")),
                new { phase = job.Phase, error = error.Message, at = DateTimeOffset.UtcNow });
            if (File.Exists(At("transaction.json"))) {
                try {
                    await Recover(UpdateContract.Read<JsonObject>(At("transaction.json")));
                    Phase(job.Phase, job.Message + "；失败原因：" + error.Message);
                }
                catch (Exception rollback) { Phase("recovery_required", "更新失败：" + error.Message + "；回滚未确认：" + rollback.Message); }
            } else if (job != null) Phase(job.Phase == "waiting" ? "deferred" : "failed", error.Message);
        } finally {
            try {
                if (runtime == null && releaseDrain) {
                var url = new Uri(config.HealthUrl).GetLeftPart(UriPartial.Authority) + "/api/server-update/release-drain";
                using var request = new HttpRequestMessage(HttpMethod.Post, url); request.Headers.Add("X-RemoteTool-Updater", config.WorkerToken);
                using var result = await http.SendAsync(request);
                }
            } catch { /* A failed Web process is recovered from the durable transaction next run. */ }
        }
    }
}

/// <summary>用户在等待空闲期间取消；由 cancel.json 触发。</summary>
public sealed class UpdateCancelled : Exception {
    public UpdateCancelled() : base("已取消等待") { }
}
