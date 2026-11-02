using System.Text.Json;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Services;

/// <summary>The system owns maintenance; IIS schedules it and reads the durable journal.</summary>
public sealed class HermesGatewayAdmin(HermesManagement management)
{
    private async Task<JsonObject> Invoke(string action, object body, CancellationToken ct) {
        using (HermesProfileScope.Enter("default")) {
            var result = await management.Invoke("admin_gateway_" + action, JsonSerializer.SerializeToElement(body), ct);
            return JsonNode.Parse(result.GetRawText())!.AsObject();
        }
    }
    // Hermes 被卸载、venv 损坏或网关拉不起来时也要给出可读原因，不要整页 500
    private static string FriendlyError(Exception error) {
        var text = error.Message ?? "";
        return text.Contains("No such file", StringComparison.OrdinalIgnoreCase)
            || text.Contains("cannot find", StringComparison.OrdinalIgnoreCase)
            || text.Contains("ENOENT", StringComparison.OrdinalIgnoreCase)
            ? "本机未安装 Hermes 或运行环境缺失，可在 Agent 设置里一键安装"
            : text;
    }
    public async Task<JsonObject> Status(CancellationToken ct) {
        JsonObject result;
        try { result = await Invoke("job", new { }, ct); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception error) { return new JsonObject { ["read_error"] = FriendlyError(error) }; }
        try { result["gateway"] = await Invoke("status", new { }, ct); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception error) { result["read_error"] = FriendlyError(error); }
        return result;
    }
    public async Task<JsonObject> Log(long offset, string key, CancellationToken ct) => await Invoke("log", new { offset, key }, ct);
    public async Task<JsonObject> Start(bool wait, CancellationToken ct, string mode = "restart") {
        if (!OperatingSystem.IsWindows()) return await UnixStart(ct);
        management.BeginMaintenance();
        try {
            var live = await Invoke("status", new { }, ct);
            if (live["session_id"]?.GetValue<int>() is not > 0)
                throw new InvalidOperationException("网关未运行在交互式用户会话中，请先在电脑上修复网关启动任务");
            if (live["restart_supported"]?.GetValue<bool>() != true || live["running"]?.GetValue<bool>() != true || live["fresh"]?.GetValue<bool>() != true)
                throw new InvalidOperationException("当前网关不支持安全重启或状态不可用");
            if (!wait && live["active"]!.GetValue<int>() > 0) throw new InvalidOperationException("有任务正在运行，请选择等待空闲后重启");
            var job = await Invoke("prepare", new { wait, mode }, ct);
            try {
                HermesMaintenanceScheduler.Start(job["python"]!.ToString(), job["script"]!.ToString(), job["request"]!.ToString());
            } catch (Exception error) {
                await Invoke("launch_failed", new { key = Path.GetFileName(Path.GetDirectoryName(job["request"]!.ToString())), error = error.Message }, CancellationToken.None);
                throw new InvalidOperationException(error.Message, error);
            }
            return await Invoke("job", new { }, ct);
        } finally { management.EndMaintenance(); }
    }
    /// <summary>Linux/macOS：走 Hermes 自己的 systemd/launchd 用户服务，不用 Windows 计划任务。</summary>
    private async Task<JsonObject> UnixStart(CancellationToken ct) {
        management.BeginMaintenance();
        try {
            using (HermesProfileScope.Enter("default")) {
                var (python, _, home) = management.Runtime();
                if (!File.Exists(python)) throw new InvalidOperationException("服务端尚未安装 Hermes，请先使用一键安装。");
                var result = await HermesUnixGateway.Restart(python, home, ct);
                return new JsonObject {
                    ["ok"] = result.Ok, ["message"] = result.Message, ["port"] = result.Port, ["platform"] = "unix",
                };
            }
        } finally { management.EndMaintenance(); }
    }
    public async Task<JsonObject> Cancel(CancellationToken ct) => await Invoke("cancel_job", new { }, ct);
}
