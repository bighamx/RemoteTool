using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services.Scheduled;

/// <summary>定时消息复用普通发送和桌面协作流程，忙碌时留在持久化待发列表。</summary>
public sealed class ScheduledMessageDispatcher : BackgroundService
{
    private static readonly TimeSpan Tick = TimeSpan.FromSeconds(30);
    private static readonly long[] RetryDelays = [30, 120, 600];
    private readonly ScheduledMessageStore store;
    private readonly ILogger<ScheduledMessageDispatcher> log;
    private readonly Func<HttpMethod, string, JsonElement?, CancellationToken, string, Task<HttpResponseMessage>> send;

    public ScheduledMessageDispatcher(ScheduledMessageStore store, CodexBridge bridge, ILogger<ScheduledMessageDispatcher> log)
        : this(store, (method, path, body, ct, key) => bridge.SendAsync(method, path, body, ct, key), log) { }

    internal ScheduledMessageDispatcher(ScheduledMessageStore store,
        Func<HttpMethod, string, JsonElement?, CancellationToken, string, Task<HttpResponseMessage>> send,
        ILogger<ScheduledMessageDispatcher> log)
    {
        this.store = store;
        this.send = send;
        this.log = log;
    }

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
        foreach (var message in store.List().Where(row => row.Status is ScheduledMessageStatus.Sending or ScheduledMessageStatus.DeliveryUnknown))
        {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(45));
            try { await Reconcile(message, timeout.Token); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception) { Unknown(message); }
        }
        var now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        foreach (var message in store.Due(now))
        {
            ct.ThrowIfCancellationRequested();
            if (now - (message.ScheduledAt ?? message.SendAt) > ScheduledMessageStore.MissedWindowSeconds)
            {
                Finish(message, ScheduledMessageStatus.Failed, null, "错过发送时间超过 30 分钟，已跳过");
                continue;
            }
            if (!store.BeginSend(message.Id)) continue;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(45));
            var dispatched = false;
            try
            {
                using var archived = await send(HttpMethod.Get,
                    $"sessions/{Uri.EscapeDataString(message.SessionId)}/archived-state", null, timeout.Token, null);
                if (!archived.IsSuccessStatusCode)
                {
                    Retry(message, "暂时无法核对会话归档状态", now);
                    continue;
                }
                var state = JsonNode.Parse(await archived.Content.ReadAsStringAsync(timeout.Token));
                if (state?["archived"]?.GetValue<bool>() == true)
                {
                    Finish(message, ScheduledMessageStatus.Cancelled, null, "会话已归档，定时消息已取消");
                    continue;
                }
                var body = JsonSerializer.SerializeToElement(new {
                    session_id = message.SessionId, input = message.Text, defer_if_busy = true
                });
                dispatched = true;
                using var response = await send(HttpMethod.Post, "runs", body, timeout.Token, "sched-" + message.Id);
                var payload = JsonNode.Parse(await response.Content.ReadAsStringAsync(timeout.Token));
                if (response.IsSuccessStatusCode && payload?["delivery"]?.ToString() == "accepted")
                {
                    Finish(message, ScheduledMessageStatus.Sent, payload["run_id"]?.ToString(), "");
                    continue;
                }
                var code = payload?["code"]?.ToString() ?? "";
                var text = payload?["message"]?.ToString() ?? $"发送失败（HTTP {(int)response.StatusCode}）";
                if (code == "session_writer_busy")
                {
                    store.Postpone(message.Id, now + 30, "会话正在运行，等待空闲后发送", countAttempt: false);
                    log.LogInformation("定时消息 {ScheduleId} 会话 {SessionId} 等待空闲", message.Id, message.SessionId);
                }
                else if (payload?["delivery"]?.ToString() == "rejected" || code == "thread_missing")
                    Finish(message, ScheduledMessageStatus.Failed, null, text);
                else await Reconcile(message, timeout.Token);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception)
            {
                if (dispatched) Unknown(message);
                else Retry(message, "暂时无法连接会话服务", now);
            }
        }
        store.Reap(now);
    }

    private async Task Reconcile(ScheduledMessage message, CancellationToken ct)
    {
        using var response = await send(HttpMethod.Get, "runs/lookup?key=sched-" + Uri.EscapeDataString(message.Id), null, ct, null);
        if (!response.IsSuccessStatusCode) { Unknown(message); return; }
        var receipt = JsonNode.Parse(await response.Content.ReadAsStringAsync(ct));
        if (receipt?["found"]?.GetValue<bool>() != true || receipt["session_id"]?.ToString() != message.SessionId)
        {
            Unknown(message);
            return;
        }
        var delivery = receipt["delivery"]?.ToString();
        if (delivery == "accepted") Finish(message, ScheduledMessageStatus.Sent, receipt["run_id"]?.ToString(), "");
        else if (delivery == "rejected") Finish(message, ScheduledMessageStatus.Failed, receipt["run_id"]?.ToString(), "服务端确认未接收定时消息，请重新设置发送时间");
        else Unknown(message);
    }

    private void Unknown(ScheduledMessage message)
    {
        if (store.Find(message.Id)?.Status == ScheduledMessageStatus.DeliveryUnknown) return;
        store.Complete(message.Id, ScheduledMessageStatus.DeliveryUnknown, null, "发送结果待确认，正在查询原请求回执；不会自动重发，请核对会话消息");
        log.LogWarning("定时消息 {ScheduleId} 会话 {SessionId} 投递结果待确认", message.Id, message.SessionId);
    }

    private void Finish(ScheduledMessage message, string status, string runId, string error)
    {
        store.Complete(message.Id, status, runId, error);
        log.LogInformation("定时消息 {ScheduleId} 会话 {SessionId} 状态 {Status}，尝试次数 {Attempts}", message.Id, message.SessionId, status, message.Attempts + 1);
    }

    private void Retry(ScheduledMessage message, string error, long now)
    {
        if (message.Attempts >= RetryDelays.Length || now + RetryDelays[message.Attempts] -
            (message.ScheduledAt ?? message.SendAt) > ScheduledMessageStore.MissedWindowSeconds)
        {
            Finish(message, ScheduledMessageStatus.Failed, null, "多次检查仍无法发送：" + error);
            return;
        }
        store.Postpone(message.Id, now + RetryDelays[message.Attempts], error);
        log.LogWarning("定时消息 {ScheduleId} 会话 {SessionId} 将重试检查，次数 {Attempts}", message.Id, message.SessionId, message.Attempts + 1);
    }
}
