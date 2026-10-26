using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services.Scheduled;

/// <summary>
/// 到点把定时消息投递到 codex 队列。复用现有 sessions/{id}/queue（action=add）：
/// runtime 不在会自动拉起并 resume，会话忙时排队、空闲时立即开始，key 天然幂等。
/// </summary>
public sealed class ScheduledMessageDispatcher(
    ScheduledMessageStore store,
    CodexBridge bridge,
    ILogger<ScheduledMessageDispatcher> log) : BackgroundService
{
    private static readonly TimeSpan Tick = TimeSpan.FromSeconds(30);
    /** 重试顺延：30 秒、2 分钟、10 分钟后；第 4 次仍失败则标记 failed。 */
    private static readonly long[] RetryDelays = [30, 120, 600];

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await TickAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error) { log.LogWarning(error, "定时消息调度轮询失败"); }
            try { await Task.Delay(Tick, stoppingToken); }
            catch (OperationCanceledException) { break; }
        }
    }

    internal async Task TickAsync(CancellationToken ct)
    {
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        foreach (var message in store.Due(now))
        {
            if (now - message.SendAt > ScheduledMessageStore.MissedWindowSeconds)
            {
                store.Complete(message.Id, ScheduledMessageStatus.Failed, null,
                    "错过发送时间超过 30 分钟，已跳过");
                continue;
            }
            if (!store.BeginSend(message.Id)) continue;
            try
            {
                if (await IsArchived(message.SessionId, ct))
                {
                    store.Complete(message.Id, ScheduledMessageStatus.Cancelled, null,
                        "会话已归档，定时消息已取消");
                    continue;
                }
                var body = JsonSerializer.SerializeToElement(new
                {
                    action = "add",
                    input = message.Text,
                    key = "sched-" + message.Id
                });
                using var response = await bridge.SendAsync(HttpMethod.Post,
                    $"sessions/{Uri.EscapeDataString(message.SessionId)}/queue", body, ct);
                var payload = await response.Content.ReadAsStringAsync(ct);
                if (response.IsSuccessStatusCode)
                {
                    store.Complete(message.Id, ScheduledMessageStatus.Sent, RunId(payload), "");
                    continue;
                }
                var (code, text) = Error(payload);
                if (code == "thread_missing")
                {
                    store.Complete(message.Id, ScheduledMessageStatus.Failed, null,
                        text.Length > 0 ? text : "会话已不存在");
                    continue;
                }
                // 归档检查之外的兜底：codex 自己拒绝已归档会话时同样按取消处理。
                if (text.Contains("is archived", StringComparison.OrdinalIgnoreCase))
                {
                    store.Complete(message.Id, ScheduledMessageStatus.Cancelled, null,
                        "会话已归档，定时消息已取消");
                    continue;
                }
                Retry(message, text.Length > 0 ? text : $"发送失败（HTTP {(int)response.StatusCode}）", now);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception error)
            {
                Retry(message, error.Message, now);
            }
        }
        store.Reap(now);
    }

    private void Retry(ScheduledMessage message, string error, long now)
    {
        if (message.Attempts >= RetryDelays.Length)
        {
            store.Complete(message.Id, ScheduledMessageStatus.Failed, null,
                "多次重试仍未发送：" + error);
            return;
        }
        var next = now + RetryDelays[message.Attempts];
        // 超过 30 分钟补发窗口就不必再排期了。
        if (next - message.SendAt > ScheduledMessageStore.MissedWindowSeconds)
        {
            store.Complete(message.Id, ScheduledMessageStatus.Failed, null, error);
            return;
        }
        store.Postpone(message.Id, next, error);
    }

    /// <summary>会话已归档就取消（用户约定）。查询失败按未归档处理，避免误取消。</summary>
    private async Task<bool> IsArchived(string sessionId, CancellationToken ct)
    {
        try
        {
            using var response = await bridge.SendAsync(HttpMethod.Get,
                $"sessions/{Uri.EscapeDataString(sessionId)}/archived-state", null, ct);
            if (!response.IsSuccessStatusCode) return false;
            var payload = JsonNode.Parse(await response.Content.ReadAsStringAsync(ct)) as JsonObject;
            return payload?["archived"]?.GetValue<bool>() == true;
        }
        catch (Exception) { return false; }
    }

    private static string RunId(string payload)
    {
        try
        {
            var json = JsonNode.Parse(payload) as JsonObject;
            return json?["queuedSubmission"]?["id"]?.GetValue<string>() ?? "";
        }
        catch (Exception) { return ""; }
    }

    private static (string Code, string Text) Error(string payload)
    {
        try
        {
            var json = JsonNode.Parse(payload) as JsonObject;
            return (json?["code"]?.GetValue<string>() ?? "", json?["message"]?.GetValue<string>() ?? "");
        }
        catch (Exception) { return ("", ""); }
    }
}
