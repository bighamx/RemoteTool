using RemoteTool.WebApi.Services.Scheduled;

internal static class ScheduledMessageChecks
{
    public static Task Run()
    {
        var root = Path.Combine(Path.GetTempPath(), "remotetool-scheduled-" + Guid.NewGuid().ToString("N"));
        try
        {
            var path = Path.Combine(root, "scheduled-messages.json");
            var now = 1_800_000_000L;
            var store = new ScheduledMessageStore(path);

            // 到点前不可见，到点后进入 Due；认领后不会再次被 Due 选中。
            var first = store.Add("session-a", "到点发送", now + 60, "Asia/Shanghai", "client-1", now);
            if (store.Due(now + 59).Count != 0) throw new Exception("Future message must not be due");
            if (store.Due(now + 60).Count != 1) throw new Exception("Due message must be selected");
            if (!store.BeginSend(first.Id) || store.BeginSend(first.Id)) throw new Exception("Claim must be exclusive");
            if (store.Due(now + 61).Count != 0) throw new Exception("Claimed message must leave the due set");
            store.Complete(first.Id, ScheduledMessageStatus.Sent, "run-1", "");
            if (store.Find(first.Id) is not { Status: ScheduledMessageStatus.Sent, RunId: "run-1" }) throw new Exception("Sent state must persist");

            // 同一 clientKey 重复提交返回同一条，避免离线重试重复投递。
            var again = store.Add("session-a", "到点发送", now + 60, "Asia/Shanghai", "client-1", now + 5);
            if (again.Id != first.Id) throw new Exception("Duplicate client key must be idempotent");

            // 重试顺延回到 pending，并累计尝试次数。
            var retry = store.Add("session-a", "重试", now + 10, "", "", now);
            store.BeginSend(retry.Id);
            store.Postpone(retry.Id, now + 40, "busy");
            if (store.Find(retry.Id) is not { Status: ScheduledMessageStatus.Pending, Attempts: 1, SendAt: var at } || at != now + 40)
                throw new Exception("Postpone must reschedule and count the attempt");

            // 取消只能作用于 pending。
            var cancel = store.Add("session-a", "取消", now + 20, "", "", now);
            store.Cancel(cancel.Id, "已取消");
            if (store.Find(cancel.Id)?.Status != ScheduledMessageStatus.Cancelled) throw new Exception("Cancel must persist");
            try { store.Cancel(cancel.Id, "again"); throw new Exception("Cancelled message must not cancel twice"); }
            catch (InvalidOperationException) { }

            // 校验：空文本、无效会话、过远/过近时间、单会话上限。
            void Reject(Action action, string name)
            {
                try { action(); } catch (ArgumentException) { return; }
                throw new Exception(name);
            }
            Reject(() => store.Add("session-a", "   ", now + 30, "", "", now), "Blank text must be rejected");
            Reject(() => store.Add("bad id!", "x", now + 30, "", "", now), "Invalid session must be rejected");
            Reject(() => store.Add("session-a", "x", now - ScheduledMessageStore.MissedWindowSeconds - 1, "", "", now), "Stale send time must be rejected");
            Reject(() => store.Add("session-a", "x", now + ScheduledMessageStore.MaxLeadSeconds + 1, "", "", now), "Overly distant send time must be rejected");
            for (var i = store.List("session-a", ScheduledMessageStatus.Pending).Count; i < ScheduledMessageStore.MaxPendingPerSession; i++)
                store.Add("session-a", "填充 " + i, now + 100 + i, "", "", now);
            Reject(() => store.Add("session-a", "超限", now + 400, "", "", now), "Per-session pending cap must hold");

            // 重启后状态不丢；7 天前的终态记录会被清理。
            var restored = new ScheduledMessageStore(path);
            if (restored.List(status: ScheduledMessageStatus.Sent).Count != 1) throw new Exception("State must survive restart");
            restored.Reap(now + 8L * 24 * 3600);
            if (restored.List().Any(row => row.Id == first.Id)) throw new Exception("Expired terminal records must be reaped");
            return Task.CompletedTask;
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
