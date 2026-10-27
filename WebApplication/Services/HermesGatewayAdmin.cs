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
    public async Task<JsonObject> Status(CancellationToken ct) {
        var result = await Invoke("job", new { }, ct);
        try { result["gateway"] = await Invoke("status", new { }, ct); }
        catch (Exception error) when (error is IOException or InvalidOperationException) { result["read_error"] = error.Message; }
        return result;
    }
    public async Task<JsonObject> Log(long offset, string key, CancellationToken ct) => await Invoke("log", new { offset, key }, ct);
    public async Task<JsonObject> Start(bool wait, CancellationToken ct, string mode = "restart") {
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
    public async Task<JsonObject> Cancel(CancellationToken ct) => await Invoke("cancel_job", new { }, ct);
}
