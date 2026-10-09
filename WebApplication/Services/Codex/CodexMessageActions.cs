using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal sealed partial class CodexAgent {
    private async Task<JsonObject> MessageAction(string id, string action, JsonObject body, CancellationToken ct) {
        await settingsLock.WaitAsync(ct);
        try {
            var thread = (await ReadThread(id, true, ct))["thread"]!;
            var turns = thread.A("turns");
            var target = turns.SelectMany(turn => turn.A("items").Select(item => (turn, item)))
                .SingleOrDefault(pair => pair.item.S("type") is "userMessage" or "agentMessage" && MessageId(pair.item.S("id")) == body.L("message_id"));
            if (target.item == null) throw new CodexError("消息已变更或未保存，请刷新后重试", 409);
            if (action == "fork") {
                if (target.turn.S("status") == "inProgress") throw new CodexError("请等待该轮任务结束再分叉", 409);
                var owner = NewRuntime();
                JsonObject created;
                try {
                    await owner.Initialize(ct);
                    created = await owner.Call("thread/fork", Obj(("threadId", id), ("lastTurnId", target.turn.S("id")), ("ephemeral", false)), ct);
                } catch { await owner.DisposeAsync(); throw; }
                var fork = created["thread"]!;
                var forkId = fork.S("id");
                lock (gate) { sessionRpcs[forkId] = owner; loaded[forkId] = created; }
                var name = thread.S("name", "手机 Codex 对话") + " · 分叉";
                if (name.Length > 120) name = name[..120];
                await owner.Call("thread/name/set", Obj(("threadId", forkId), ("name", name)), ct);
                fork["name"] = name; titles.Register(forkId, name, false);
                lock (gate) if (sessionOverrides.TryGetValue(id, out var tuning)) { sessionOverrides[forkId] = tuning.DeepClone().AsObject(); PersistModels(); }
                await ReleaseSessionCore(forkId);
                await NotifyDesktop(new[] { forkId });
                var mapping = new JsonObject();
                foreach (var turn in fork.A("turns")) foreach (var item in turn.A("items"))
                    if (item.S("type") is "userMessage" or "agentMessage") mapping[MessageId(item.S("id")).ToString()] = MessageId(item.S("id")).ToString();
                return Obj(("session", Session(fork)), ("message_id_map", mapping));
            }
            if (target.item.S("type") != "userMessage" || !ReferenceEquals(target.item, target.turn.A("items").FirstOrDefault(item => item.S("type") == "userMessage")))
                throw new CodexError("仅可编辑该轮最初的用户消息；插话可使用分叉", 400);
            lock (gate) if (active.ContainsKey(id)) throw new CodexError("请先等待任务结束再编辑", 409);
            if (thread["status"].S("type") == "active" || CodexRollout.IsRunning(home, RolloutPath(thread))) throw new CodexError("此会话正在执行，请结束任务后再编辑", 409);
            if (body.S("expected_last_turn_id").Length == 0 || body.S("expected_last_turn_id") != turns.LastOrDefault().S("id"))
                throw new CodexError("会话已有新消息，请刷新后重新编辑", 409);
            try {
                var runtime = await EnsureRuntime(id);
                await runtime.Call("thread/resume", await ResumeSessionRequest(id), ct);
                await runtime.Call("thread/revert", Obj(("threadId", id), ("beforeTurnId", target.turn.S("id"))), ct);
                lock (gate) loaded.Remove(id);
                return Obj(("rewound", true));
            } catch (CodexError error) { throw TranslateBusyThread(id, error); }
        } finally { settingsLock.Release(); }
    }
}
