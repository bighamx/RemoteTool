using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

internal sealed partial class CodexAgent
{
    private async Task<JsonObject> Queue(string session, string method, JsonObject body, CancellationToken ct) {
        if (method == "GET") {
            CodexRpc owner; lock (gate) sessionRpcs.TryGetValue(session, out owner);
            var owned = owner?.Running == true;
            // Only admit native work to a runtime whose notifications this bridge owns.
            if (!owned) {
                var thread = (await ReadThread(session, false, ct))["thread"];
                if (CodexRollout.WriterLocked(home, RolloutPath(thread))) return Obj(("mode", "local"), ("data", new JsonArray()));
            }
            try {
                var result = await (owned ? owner : rpc).Call("thread/queue/list", Obj(("threadId", session), ("limit", 100)), ct);
                result["mode"] = "native"; return result;
            } catch (CodexError error) when (error.Message.Contains("Unknown", StringComparison.OrdinalIgnoreCase) || error.Message.Contains("not supported", StringComparison.OrdinalIgnoreCase)) {
                return Obj(("mode", "local"), ("data", new JsonArray()));
            }
        }
        await settingsLock.WaitAsync(ct);
        try {
            var action = body.S("action");
            var request = Obj(("threadId", session));
            CodexRpc queueOwner; lock (gate) sessionRpcs.TryGetValue(session, out queueOwner);
            var queueRpc = queueOwner?.Running == true ? queueOwner : rpc;
            if (action == "add" && queueOwner?.Running != true) {
                var thread = (await ReadThread(session, false, ct))["thread"];
                if (CodexRollout.WriterLocked(home, RolloutPath(thread)))
                    throw new CodexError("会话的执行连接已变化，请刷新队列后重试", 409);
                try {
                    var requestToResume = await ResumeSessionRequest(session);
                    queueRpc = await EnsureRuntime(session);
                    var resumed = await queueRpc.Call("thread/resume", requestToResume, ct);
                    lock (gate) loaded[session] = resumed;
                } catch { await ReleaseSessionCore(session); throw; }
            }
            if (action is "add" or "update") {
                request["input"] = CodexAttachmentInput.Build(body.S("input"), body.A("attachment_paths"), settings.S("attachments"), session);
                string queuedRun = null;
                if (action == "add") {
                    var key = body.S("key");
                    if (!System.Text.RegularExpressions.Regex.IsMatch(key,"^[a-zA-Z0-9_-]{16,120}$"))throw new CodexError("缺少队列消息标识");
                    request["clientUserMessageId"] = key;
                    queuedRun = "codex_" + Guid.NewGuid().ToString("N");
                    lock (gate) {
                        var old = runs.FirstOrDefault(entry=>entry.Value.S("key")==key);
                        if(old.Value!=null)throw new CodexError("此队列消息已提交，请刷新队列核对",409);
                        runs[queuedRun]=Obj(("run_id",queuedRun),("session_id",session),("key",key),("status","queued"),
                            ("native_queue",true),("output",""),("enqueued_at",DateTimeOffset.UtcNow.ToUnixTimeSeconds()));Persist();
                    }
                }
                else request["queuedSubmissionId"] = body.S("id");
                try {
                    var result = await queueRpc.Call("thread/queue/" + action, request, ct);
                    if(queuedRun!=null)lock(gate){runs[queuedRun]!["native_queue_id"]=result["queuedSubmission"].S("id");Persist();}
                    if (action == "add") _ = StartQueuedIfIdle(session);
                    return result;
                } catch(CodexError error) {
                    if(queuedRun!=null)lock(gate){runs[queuedRun]!["status"]=error.Delivery=="rejected"?"failed":"acceptance_unknown";Persist();}
                    throw;
                }
            }
            if (action == "delete") {
                request["queuedSubmissionId"] = body.S("id");
                var result=await queueRpc.Call("thread/queue/delete", request, ct);
                if(result.B("deleted"))lock(gate){foreach(var row in runs.Where(entry=>entry.Value.S("session_id")==session && entry.Value.S("native_queue_id")==body.S("id"))){row.Value!["status"]="cancelled";}Persist();}
                _ = ReleaseSession(session);
                return result;
            }
            throw new CodexError("无效队列操作");
        } finally { settingsLock.Release(); }
    }

    private async Task StartQueuedIfIdle(string session, bool completedTurn = false) {
        await settingsLock.WaitAsync();
        try {
            CodexRpc owner;
            lock (gate) { if (active.ContainsKey(session)) return; sessionRpcs.TryGetValue(session, out owner); }
            if (owner?.Running != true) return;
            var next = (await owner.Call("thread/queue/list", Obj(("threadId", session), ("limit", 1)))).A("data").FirstOrDefault();
            if (next == null) return;
            lock (gate) if (!runs.Any(entry => entry.Value.B("native_queue") && entry.Value.S("session_id") == session &&
                entry.Value.S("native_queue_id") == next.S("id") && entry.Value.S("status") == "queued")) return;
            try { if (!completedTurn) {
                var thread = (await owner.Call("thread/read", Obj(("threadId", session), ("includeTurns", false))))["thread"];
                if (thread?["status"].S("type") == "active") return;
            } } catch (CodexError error) when (error.Message.Contains("rollout", StringComparison.OrdinalIgnoreCase) && error.Message.Contains("empty", StringComparison.OrdinalIgnoreCase)) {
                // Fresh threads have a live subscription but no persisted first message yet.
            }
            // An exact native submission ID makes racing automatic consumption harmless:
            // it can only be removed/started once, and never becomes an interjection.
            await owner.Call("thread/queue/start", Obj(("threadId", session), ("queuedSubmissionId", next.S("id"))));
        } catch (CodexError) { /* Keep native queue entries; never retry a new turn/input write. */ }
        finally { settingsLock.Release(); }
    }

    private async Task BindQueuedNativeTurn(string session, string turn, CodexRpc owner) {
        owner ??= rpc;
        JsonObject[] candidates;
        lock(gate) {
            if(active.ContainsKey(session))return;
            candidates=runs.Select(entry=>entry.Value as JsonObject).Where(row=>row!=null && row.B("native_queue") &&
                row.S("session_id")==session && row.S("status")=="queued").ToArray();
        }
        if(candidates.Length==0)return;
        var waiting=(await owner.Call("thread/queue/list",Obj(("threadId",session),("limit",100)))).A("data").Select(row=>row.S("id")).ToHashSet();
        lock(gate) {
            if(active.ContainsKey(session))return;
            // A delete/update can finish while the list query is in flight. Recheck the
            // journal under the lock so a removed entry is never mistaken for a started turn.
            var consumed=candidates.Where(row=>row.S("status")=="queued" &&
                (row.S("native_queue_id").Length==0 || !waiting.Contains(row.S("native_queue_id")))).ToArray();
            if(consumed.Length!=1)return;
            var run=consumed[0].S("run_id");runs[run]!["status"]="started";runs[run]!["turn_id"]=turn;
            runs[run]!["created_at"]=DateTimeOffset.UtcNow.ToUnixTimeSeconds();
            active[session]=run;Persist();
        }
    }

    private async Task<bool> NativeQueueStillOwns(string session) {
        CodexRpc owner;bool queued;
        lock(gate){sessionRpcs.TryGetValue(session,out owner);queued=runs.Any(entry=>entry.Value.B("native_queue") && entry.Value.S("session_id")==session && entry.Value.S("status")=="queued");}
        if(!queued || owner?.Running!=true)return false;
        // A consumed submission may be awaiting its ordered turn/started notification.
        // Snapshot thread status can lag, so retain the owner until the journal binds it.
        await Task.CompletedTask;
        return true;
    }
}
