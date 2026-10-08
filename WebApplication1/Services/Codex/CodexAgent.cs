using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using System.Collections.Concurrent;
using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

internal sealed partial class CodexAgent : IAsyncDisposable
{
    private readonly JsonObject settings;
    private readonly string folder, home, journal;
    private readonly CodexAccountStore accounts;
    private readonly CodexHandoff handoff;
    private readonly CodexTitleGenerator titles;
    private readonly object gate = new();
    private readonly SemaphoreSlim settingsLock = new(1, 1);
    private JsonObject providers, runs;
    private CodexRpc rpc;
    private readonly Dictionary<string, CodexRpc> sessionRpcs = new();
    private readonly Dictionary<string, JsonObject> loaded = new();
    private readonly Dictionary<string, string> active = new();
    private readonly Dictionary<string, List<JsonObject>> events = new();
    private readonly Dictionary<string, JsonObject> approvals = new();
    private readonly HashSet<string> replyingApprovals = new();
    private CodexRpc loginRpc;
    private string loginFolder, loginName;
    private JsonObject login;
    private readonly Dictionary<string, JsonObject> workspaceUsage = new();
    private string runtimeWorkspace = "";
    private string CurrentWorkspace() { try { return CodexAccountStore.Identity(accounts.CurrentAuth()).S("workspace_id"); } catch (CodexError) { return ""; } }
    private readonly HashSet<string> desktopAnnounced = new();
    private readonly Dictionary<string, JsonObject> sessionOverrides = new();
    private readonly Dictionary<string, JsonObject> contexts = new();
    private async Task ReleaseSession(string id) {
        await settingsLock.WaitAsync();
        try {
            lock (gate) if (active.ContainsKey(id)) return;
            if (await NativeQueueStillOwns(id)) return;
            lock (gate) if (active.ContainsKey(id)) return;
            await ReleaseSessionCore(id);
        } catch (Exception error) when (error is CodexError or IOException or InvalidOperationException or OperationCanceledException) { }
        finally { settingsLock.Release(); }
    }
    private async Task ReleaseAndNotify(string id) {
        await ReleaseSession(id);
        await NotifyDesktop(new[] { id }, true);
    }
    private async Task ReleaseSessionCore(string id) {
        CodexRpc owner;
        lock (gate) { sessionRpcs.Remove(id, out owner); loaded.Remove(id); }
        // Unsubscribe keeps a runtime alive for 30 minutes. An owned, per-session
        // connection can be closed without interrupting any other conversation.
        if (owner != null) await owner.DisposeAsync();
    }
    private CodexRpc Runtime(string id) {
        lock (gate) return sessionRpcs.GetValueOrDefault(id) ?? throw new CodexError("原任务连接已释放，本次消息未写入，请刷新后重新发送。", 409, "run_stale");
    }
    private CodexRpc NewRuntime() {
        var environment = new Dictionary<string, string>();
        foreach (var entry in providers) if (entry.Value.S("api_key").Length > 0) environment["CHUCKIE_CODEX_" + entry.Key.ToUpperInvariant() + "_KEY"] = entry.Value.S("api_key");
        CodexRpc client = null;
        client = new CodexRpc(settings.S("executable"), home, environment, message => Notification(message, client), "cli_auth_credentials_store=\"file\"");
        return client;
    }
    private async Task<CodexRpc> EnsureRuntime(string id) {
        CodexRpc client; lock (gate) sessionRpcs.TryGetValue(id, out client);
        if (client?.Running == true) return client;
        if (client != null) await ReleaseSessionCore(id);
        client = NewRuntime();
        try { await client.Initialize(); lock (gate) sessionRpcs[id] = client; return client; }
        catch { await client.DisposeAsync(); throw; }
    }
    private Task<JsonObject> ReadThread(string id, bool turns, CancellationToken ct = default) {
        CodexRpc reader; lock (gate) sessionRpcs.TryGetValue(id, out reader);
        return (reader?.Running == true ? reader : rpc).Call("thread/read", Obj(("threadId", id), ("includeTurns", turns)), ct);
    }
    private async Task<JsonObject> ResumeSessionRequest(string id) {
        var request = Obj(("threadId", id));
        var thread = (await ReadThread(id, false))["thread"]!;
        if (thread.S("projectId").Length > 0) {
            var project = (await rpc.Call("project/read", Obj(("projectId", thread.S("projectId")))))["project"]!;
            request["runtimeWorkspaceRoots"] = new JsonArray(project.A("roots").Select(root => (JsonNode)JsonValue.Create(root.S("path"))).ToArray());
        }
        lock (gate) if (sessionOverrides.TryGetValue(id, out var selected)) CodexModelSettings.ApplyResume(request, selected);
        return request;
    }
    private async Task NotifyDesktop(IEnumerable<string> ids, bool force = false) {
        string[] pending;
        lock (gate) pending = ids.Where(id => force || !desktopAnnounced.Contains(id)).Distinct().ToArray();
        if (pending.Length > 0 && await CodexDesktopSync.NotifyAvailable(pending)) lock (gate) foreach (var id in pending) desktopAnnounced.Add(id);
    }

    private async Task<JsonObject> WorkspaceUsage(string id, bool refresh, CancellationToken ct) {
        await settingsLock.WaitAsync(ct);
        try {
            var auth = accounts.WorkspaceAuth(id);
            var fingerprint = Hash(auth.ToJsonString());
            if (!refresh && workspaceUsage.TryGetValue(id, out var cached) && cached.S("auth_fingerprint") == fingerprint && cached.L("checked_at") > DateTimeOffset.UtcNow.ToUnixTimeSeconds() - 30) { var copy = cached.DeepClone().AsObject(); copy.Remove("auth_fingerprint"); return copy; }
            JsonObject result;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(20));
            try {
                var probe = await CodexWorkspaceUsage.Read(settings.S("executable"), folder, auth, timeout.Token);
                accounts.RecoverWorkspaceAuth(id, auth, probe.Auth);
                result = Obj(("workspace_id", id), ("chatgpt_account_id", CodexAccountStore.Identity(probe.Auth).S("workspace_id")), ("available", true), ("usage", probe.Usage), ("checked_at", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
            } catch (OperationCanceledException) when (!ct.IsCancellationRequested) {
                result = Obj(("workspace_id", id), ("available", false), ("error", "查询超时，请重试"));
            } catch (Exception error) when (error is CodexError or IOException or HttpRequestException) {
                result = Obj(("workspace_id", id), ("available", false), ("error", error is CodexError known ? known.Message : "暂时无法读取用量，请刷新重试"));
            }
            if (result.B("available")) { var cachedResult = result.DeepClone().AsObject(); cachedResult["auth_fingerprint"] = Hash(accounts.WorkspaceAuth(id).ToJsonString()); workspaceUsage[id] = cachedResult; }
            return result;
        } finally { settingsLock.Release(); }
    }
    private async Task<JsonObject> WorkspaceResetCredits(string id, CancellationToken ct) {
        await settingsLock.WaitAsync(ct);
        try {
            var auth = accounts.WorkspaceAuth(id);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(25));
            try {
                var probe = await CodexWorkspaceUsage.ReadResetCredits(settings.S("executable"), folder, auth, timeout.Token);
                accounts.RecoverWorkspaceAuth(id, auth, probe.Auth);
                return Obj(("workspace_id", id), ("chatgpt_account_id", CodexAccountStore.Identity(probe.Auth).S("workspace_id")),
                    ("available", true), ("usage", probe.Usage), ("checked_at", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
            } catch (OperationCanceledException) when (!ct.IsCancellationRequested) {
                throw new CodexError("额度重置查询超时，请重试", 504);
            }
        } finally { settingsLock.Release(); }
    }
    private async Task<JsonObject> ConsumeWorkspaceResetCredit(string id, string creditId, bool useNextAvailable, string idempotencyKey, CancellationToken ct) {
        if (!Regex.IsMatch(idempotencyKey ?? "", "^[a-zA-Z0-9_-]{16,120}$"))
            throw new CodexError("额度重置需要唯一请求标识", 400);
        await settingsLock.WaitAsync(ct);
        try {
            var auth = accounts.WorkspaceAuth(id);
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(35));
            try {
                var probe = await CodexWorkspaceUsage.ConsumeResetCredit(settings.S("executable"), folder, auth, creditId, useNextAvailable, idempotencyKey, timeout.Token);
                accounts.RecoverWorkspaceAuth(id, auth, probe.Auth);
                var usage = probe.Result["usage"]?.DeepClone() as JsonObject ?? new JsonObject();
                var cached = Obj(("workspace_id", id), ("chatgpt_account_id", CodexAccountStore.Identity(probe.Auth).S("workspace_id")),
                    ("available", true), ("usage", usage.DeepClone()), ("checked_at", DateTimeOffset.UtcNow.ToUnixTimeSeconds()),
                    ("auth_fingerprint", Hash(accounts.WorkspaceAuth(id).ToJsonString())));
                workspaceUsage[id] = cached;
                return Obj(("workspace_id", id), ("chatgpt_account_id", CodexAccountStore.Identity(probe.Auth).S("workspace_id")),
                    ("available", true), ("outcome", probe.Result["outcome"]), ("usage", usage), ("checked_at", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
            } catch (OperationCanceledException) when (!ct.IsCancellationRequested) {
                throw new CodexError("额度重置操作超时；请先刷新状态，系统不会自动重试", 504);
            }
        } finally { settingsLock.Release(); }
    }

    private CodexAgent(string config) {
        settings = Read(config); folder = settings.S("state_folder", Path.GetDirectoryName(config)!); home = settings.S("home");
        if (string.IsNullOrWhiteSpace(home) || !Path.IsPathFullyQualified(home))
            throw new InvalidOperationException("Codex bridge home must be a non-empty absolute path; regenerate connection.json through the web service.");
        titles = new CodexTitleGenerator(Path.GetDirectoryName(config)!);
        journal = Path.Combine(folder, "runs.json");
        providers = Read(Path.Combine(folder, "providers.json")); runs = Read(journal);
        handoff = new CodexHandoff(settings["handoff"], settings.S("token"), (id, state) => {
            lock (gate) {
                var merged = runs[id]?.DeepClone() as JsonObject ?? new JsonObject();
                foreach (var field in state) merged[field.Key] = field.Value?.DeepClone();
                runs[id] = merged; Persist();
            }
        });
        // An IIS recycle loses the RPC subscription and desktop follow task. A persisted
        // "started" run cannot still be observed by this process; do not advertise it forever.
        var recovered = false;
        foreach (var entry in runs) if (entry.Value is JsonObject state)
            recovered |= CodexRunRecovery.Recover(state, tracked: false);
        if (recovered) Persist();
        foreach (var entry in Read(Path.Combine(folder, "model-selections.json")))
            if (entry.Value is JsonObject selection) sessionOverrides[entry.Key] = selection.DeepClone().AsObject();
        var accountStore = settings.S("account_store");
        if (string.IsNullOrWhiteSpace(accountStore)) accountStore = Path.Combine(Path.GetDirectoryName(home) ?? home, ".codex-switch");
        accounts = new CodexAccountStore(home, accountStore);
    }
    private async Task Launch() {
        var environment = new Dictionary<string, string>();
        foreach (var entry in providers) if (entry.Value.S("api_key").Length > 0) environment["CHUCKIE_CODEX_" + entry.Key.ToUpperInvariant() + "_KEY"] = entry.Value.S("api_key");
        runtimeWorkspace = CurrentWorkspace();
        rpc = new CodexRpc(settings.S("executable"), home, environment, message => Notification(message), "cli_auth_credentials_store=\"file\"");
        await rpc.Initialize(); loaded.Clear(); workspaceUsage.Clear();
    }
    private async Task EnsureConnection() {
        if (rpc?.Running == true && runtimeWorkspace == CurrentWorkspace()) return;
        await settingsLock.WaitAsync();
        try {
            if (rpc?.Running == true && runtimeWorkspace == CurrentWorkspace()) return;
            lock (gate) if (rpc?.Running == true && active.Count > 0) throw new CodexError("电脑登录身份已切换，请先停止原工作空间的手机任务，再恢复连接", 409);
            lock (gate) {
                foreach (var run in active.Values) {
                    runs[run]!["status"] = "acceptance_unknown";
                    runs[run]!["error"] = "连接中断，请查看会话历史核对原任务；不会自动重发";
                }
                active.Clear(); approvals.Clear(); Persist();
            }
            if (rpc != null) await rpc.DisposeAsync();
            foreach (var id in sessionRpcs.Keys.ToArray()) await ReleaseSessionCore(id);
            await Launch();
        } finally { settingsLock.Release(); }
    }
    private void Persist() { lock (gate) Atomic(journal, runs); }
    private string RolloutPath(JsonNode thread) => CodexPreviewRollout.LatestPath(home, thread.S("id"), thread.S("path"));
    private CodexError TranslateBusyThread(string session, CodexError error) {
        if (error.Message.Contains("already has an active writer", StringComparison.OrdinalIgnoreCase))
            return new CodexError("其他客户端持有此会话的写入权限，本次消息未写入。可尝试中断并接管，或退出原客户端后重试。", 409, "session_writer_busy");
        if (error.Message.Contains("thread not found", StringComparison.OrdinalIgnoreCase)) {
            lock (gate) loaded.Remove(session);
            return new CodexError("当前连接未加载此会话，本次消息未写入；请刷新会话后重新发送。", 409, "thread_not_loaded");
        }
        if (error.Message.Contains("no rollout found", StringComparison.OrdinalIgnoreCase))
            return new CodexError("未找到此会话的可恢复记录，本次消息未写入。请刷新会话列表；尚未发送过消息的新会话可重新新建。", 404, "session_history_missing");
        if (error.Message.Contains("failed to load", StringComparison.OrdinalIgnoreCase))
            return new CodexError("无法恢复此会话，本次消息未写入。请退出持有它的客户端并刷新后重试。", 409, "session_load_failed");
        return error;
    }
    private async Task<JsonObject> FindActiveRun(string session, CancellationToken ct = default) {
        string owned; JsonObject[] candidates;
        lock (gate) {
            owned = active.GetValueOrDefault(session);
            candidates = runs.Select(entry => entry.Value as JsonObject).Where(row => row != null &&
                row.S("session_id") == session && row.S("owner") == "desktop" && row.S("status") == "acceptance_unknown" &&
                row.S("error_code") == "run_tracking_lost" && row.S("turn_id").Length > 0).ToArray();
        }
        if (owned != null) { lock (gate) return runs[owned]?.DeepClone().AsObject() ?? Obj(("run_id", null)); }
        if (candidates.Length == 0) return Obj(("run_id", null));
        var observed = await CodexDesktopActivity.Read(session, ct);
        string restored = null, key = null;
        lock (gate) {
            if (active.TryGetValue(session, out owned)) restored = owned;
            else {
                var matches = candidates.Where(row => CodexDesktopRunResume.CanResume(row, session, observed)).ToArray();
                // Ambiguous journals must not grant control to an unrelated task.
                if (matches.Length == 1) {
                    restored = matches[0].S("run_id"); key = matches[0].S("key");
                    CodexDesktopRunResume.Restore(runs[restored]!.AsObject());
                    active[session] = restored; Persist();
                }
            }
        }
        if (key != null) _ = ObserveDesktopRun(session, restored, key);
        lock (gate) return restored != null ? runs[restored]!.DeepClone().AsObject() : Obj(("run_id", null));
    }
    private async Task<JsonObject> ReconcileRun(string id) {
        JsonObject state; bool tracked;
        lock (gate) {
            state = runs[id]?.DeepClone() as JsonObject ?? throw new CodexError("任务记录不存在，请刷新会话", 404, "run_not_found");
            tracked = active.GetValueOrDefault(state.S("session_id")) == id;
        }
        if (state.S("status") is not ("started" or "submitting")) return state;
        if (tracked && state.S("owner") != "desktop" && state.S("phase") != "preparing") {
            CodexRpc owner; lock (gate) sessionRpcs.TryGetValue(state.S("session_id"), out owner);
            if (owner?.Running != true) tracked = false;
            else {
                var thread = (await owner.Call("thread/read", Obj(("threadId", state.S("session_id")), ("includeTurns", false))))["thread"];
                tracked = thread?["status"].S("type") != "notLoaded";
            }
        }
        if (!tracked) {
            var status = "";
            try {
                var thread = (await rpc.Call("thread/read", Obj(("threadId", state.S("session_id")), ("includeTurns", true))))["thread"];
                status = thread.A("turns").FirstOrDefault(turn => turn.S("id") == state.S("turn_id")).S("status");
            } catch (CodexError) { }
            lock (gate) {
                if (CodexRunRecovery.Recover(runs[id]!.AsObject(), false, status)) {
                    if (active.GetValueOrDefault(state.S("session_id")) == id) active.Remove(state.S("session_id"));
                    loaded.Remove(state.S("session_id")); Persist();
                }
                state = runs[id]!.DeepClone().AsObject();
            }
        }
        return state;
    }
    private JsonObject SessionControl(JsonNode thread) {
        var id = thread.S("id"); var path = RolloutPath(thread);
        var snapshot = CodexRolloutSnapshot.Read(home, path);
        var locked = CodexRollout.WriterLocked(home, path);
        var running = CodexRollout.IsRunning(home, path);
        bool mobile; lock (gate) mobile = active.ContainsKey(id) || sessionRpcs.ContainsKey(id) || handoff.OwnsSession(id);
        return Obj(("state", mobile ? "mobile" : running ? "busy" : locked ? "writer_held" : "available"),
            ("activity_id", snapshot["activity"].S("activity_id")), ("can_takeover", !mobile),
            ("message", mobile ? "手机任务正在执行" : running ? "其他客户端正在执行此会话，可等待结束或中断并接管。" : locked ? "其他端持有写入权限，手机将尝试桌面协作；无法访问时会保留草稿并提示原因。" : "可继续发送消息"));
    }
    private async Task<JsonObject> TakeoverSession(string id, JsonObject body, CancellationToken ct) {
        await settingsLock.WaitAsync(ct);
        try {
            var thread = (await ReadThread(id, false, ct))["thread"];
            var expected = body.S("expected_activity_id");
            string ownRun; lock (gate) active.TryGetValue(id, out ownRun);
            if (ownRun != null) {
                var state = await ReconcileRun(ownRun);
                if (state.S("status") is "started" or "submitting") {
                    if (expected.Length == 0 || state.S("turn_id") != expected)
                        throw new CodexError("任务已变化，请刷新后再次选择中断并接管", 409, "takeover_target_changed");
                    if (state.S("owner") == "desktop") await CodexDesktopSync.StopCompact(id);
                    else await Runtime(id).Call("turn/interrupt", Obj(("threadId", id), ("turnId", expected)), ct);
                    for (var i = 0; i < 40; i++) {
                        lock (gate) if (!active.ContainsKey(id)) break;
                        await Task.Delay(200, ct);
                    }
                    lock (gate) if (active.ContainsKey(id)) throw new CodexError("已请求中断，原任务尚未结束。请刷新状态后重试接管。", 409, "takeover_pending");
                }
            } else if (CodexRollout.IsRunning(home, RolloutPath(thread))) {
                using var probe = CancellationTokenSource.CreateLinkedTokenSource(ct); probe.CancelAfter(TimeSpan.FromSeconds(3));
                var desktop = await CodexDesktopActivity.Read(id, probe.Token);
                if (!desktop.B("available"))
                    throw new CodexError("原所有者无法访问，手机不能安全中断此任务。请退出持有该会话的 Codex 客户端后重试。", 409, "session_owner_unavailable");
                if (desktop.B("running")) {
                    if (expected.Length == 0 || desktop.S("activity_id") != expected)
                        throw new CodexError("任务已变化，请刷新后再次选择中断并接管", 409, "takeover_target_changed");
                    await CodexDesktopSync.StopCompact(id);
                    for (var i = 0; i < 20; i++) {
                        using var poll = CancellationTokenSource.CreateLinkedTokenSource(ct); poll.CancelAfter(TimeSpan.FromSeconds(2));
                        desktop = await CodexDesktopActivity.Read(id, poll.Token);
                        if (desktop.B("available") && !desktop.B("running")) break;
                        await Task.Delay(200, ct);
                    }
                    if (!desktop.B("available") || desktop.B("running"))
                        throw new CodexError("已请求中断，尚未确认原任务结束；请刷新后重试接管。", 409, "takeover_pending");
                }
            }
            try {
                var resumed = await (await EnsureRuntime(id)).Call("thread/resume", await ResumeSessionRequest(id), ct);
                lock (gate) loaded[id] = resumed;
                // Prove writability, then release the idle writer so desktop can also continue.
                await ReleaseSessionCore(id);
                return Obj(("ready", true), ("mode", "mobile"), ("message", "会话已恢复，手机可继续发送；草稿未自动发送"));
            } catch (CodexError error) when (error.Message.Contains("already has an active writer", StringComparison.OrdinalIgnoreCase)) {
                await ReleaseSessionCore(id);
                using var probe = CancellationTokenSource.CreateLinkedTokenSource(ct); probe.CancelAfter(TimeSpan.FromSeconds(3));
                var desktop = await CodexDesktopActivity.Read(id, probe.Token);
                if (!desktop.B("available")) throw new CodexError("原所有者仍持有写入权限且无法访问。请退出对应 Codex 客户端后重试。", 409, "session_owner_unavailable");
                if (desktop.B("running")) throw new CodexError("其他端已启动新任务，尚未接管；请刷新状态后重试。", 409, "takeover_target_changed");
                return Obj(("ready", true), ("mode", "desktop_cooperation"), ("message", "原任务已结束，手机可通过桌面协作继续发送；草稿未自动发送"));
            } catch (CodexError error) { await ReleaseSessionCore(id); throw TranslateBusyThread(id, error); }
        } finally { settingsLock.Release(); }
    }
    private void PersistModels() { lock (gate) Atomic(Path.Combine(folder, "model-selections.json"), new JsonObject(sessionOverrides.Select(entry => new KeyValuePair<string, JsonNode>(entry.Key, entry.Value.DeepClone())))); }
    private void Emit(string run, string kind, params (string Key, object Value)[] values) {
        lock (gate) {
            var state = runs[run]!; var seq = state.L("seq") + 1; state["seq"] = seq;
            var value = Obj(values); value["event"] = kind; value["seq"] = seq; value["run_id"] = run;
            if (!events.TryGetValue(run, out var list)) events[run] = list = new();
            list.Add(value); if (list.Count > 256) list.RemoveRange(0, list.Count - 256);
        }
    }
    private async Task Notification(JsonObject message, CodexRpc origin = null) {
        var method = message.S("method"); var p = message["params"] as JsonObject ?? new JsonObject();
        if (method == "turn/started") await BindQueuedNativeTurn(p.S("threadId"), p["turn"].S("id"), origin);
        if (method == "thread/closed") {
            lock (gate) {
                var session = p.S("threadId"); loaded.Remove(session);
                if (active.Remove(session, out var closedRun)) { CodexRunRecovery.Recover(runs[closedRun]!.AsObject(), false); Persist(); }
            }
        }
        if (method == "thread/tokenUsage/updated") {
            var usage = p["tokenUsage"];
            lock (gate) contexts[p.S("threadId")] = Obj(("available", true), ("tokens", usage?["last"].L("totalTokens")), ("limit", usage?["modelContextWindow"]), ("estimated", false));
        }
        string run; lock (gate) active.TryGetValue(p.S("threadId"), out run);
        if (message.ContainsKey("id")) {
            if (run == null) { await (origin ?? rpc).Write(Obj(("id", message["id"]), ("error", Obj(("code", -32601), ("message", "No mobile turn owns this request"))))); return; }
            var key = message["id"]!.ToJsonString();
            var approval = Obj(("request_id", key), ("kind", method.EndsWith("requestUserInput") ? "user_input" : "approval"),
                ("description", p.S("reason", p.S("command", "Codex 请求你的确认"))), ("command", p.S("command")), ("questions", p.A("questions")));
            lock (gate) { approvals[key] = message.DeepClone().AsObject(); runs[run]!["approval"] = approval.DeepClone(); }
            Emit(run, "approval.request", approval.Select(x => (x.Key, (object)x.Value)).ToArray());
            return;
        }
        if (run == null) {
            if (method == "turn/completed") { bool owned; lock (gate) owned = loaded.ContainsKey(p.S("threadId")); if (owned) _ = ReleaseAndNotify(p.S("threadId")); }
            return;
        }
        if (method == "thread/compacted" && runs[run].S("kind") == "compact") {
            lock (gate) { runs[run]!["status"] = "completed"; runs[run]!["output"] = "上下文已压缩"; active.Remove(p.S("threadId")); contexts.Remove(p.S("threadId")); Persist(); }
            Emit(run, "run.completed"); _ = ReleaseAndNotify(p.S("threadId")); return;
        }
        var item = p["item"];
        if (method == "turn/started") { lock (gate) runs[run]!["turn_id"] = p["turn"].S("id"); Persist(); }
        else if (method == "item/agentMessage/delta") {
            var delta = p.S("delta"); lock (gate) runs[run]!["output"] = runs[run].S("output") + delta;
            Emit(run, "message.delta", ("delta", delta), ("item_id", p.S("itemId")));
        } else if (method is "item/started" or "item/completed" && item.S("type") == "agentMessage") {
            Emit(run, method.EndsWith("/started") ? "message.started" : "message.completed", ("item_id", item.S("id")),
                ("phase", item.S("phase")), ("text", item.S("text")), ("timestamp", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
        } else if (method is "item/started" or "item/completed" && item != null && item.S("type") is not ("userMessage" or "agentMessage" or "reasoning")) {
            var kind = item.S("type");
            var name = item.S("tool", item.S("name", kind switch { "commandExecution" => "终端", "fileChange" => "文件修改", "mcpToolCall" => "MCP", "webSearch" => "搜索", "imageView" => "查看图片", _ => kind }));
            var preview = item.S("command", item.S("query", item.S("arguments")));
            preview = Regex.Replace(preview, "\\s+", " "); if (preview.Length > 180) preview = preview[..180];
            Emit(run, method.EndsWith("/started") ? "tool.started" : "tool.completed", ("tool", name), ("preview", preview), ("error", item.S("status") is "failed" or "declined"));
        } else if (method == "turn/completed") {
            var turn = p["turn"]; var status = turn.S("status");
            lock (gate) {
                var state = runs[run]!.AsObject(); state["status"] = status == "completed" ? "completed" : status == "interrupted" ? "interrupted" : "failed";
                state["error"] = turn?["error"].S("message"); state.Remove("approval"); active.Remove(p.S("threadId"));
            }
            Emit(run, status == "completed" ? "run.completed" : "run.failed"); Persist();
            if (status == "completed") _ = StartQueuedIfIdle(p.S("threadId"), completedTurn: true);
            _ = ReleaseAndNotify(p.S("threadId"));
        } else if (method == "serverRequest/resolved") { lock (gate) runs[run]!.AsObject().Remove("approval"); }
    }
    private JsonObject Session(JsonNode thread) {
        var result = Obj(("id", thread.S("id")), ("title", thread.S("name", thread.S("preview", "未命名会话"))),
        ("preview", thread.S("preview")), ("cwd", thread.S("cwd")), ("source", thread.S("source", "codex")),
        ("model", thread.S("model")), ("project_id", thread?["projectId"]), ("message_count", thread.A("turns").Count), ("status", thread?["status"]));
        if (thread["status"].S("type") != "active" && CodexRollout.IsRunning(home, RolloutPath(thread))) result["status"] = Obj(("type", "active"), ("activeFlags", new JsonArray()));
        return result;
    }

    private async Task<JsonObject> CreateSession(JsonObject body) {
        await settingsLock.WaitAsync();
        try {
            var mode = body.S("project_mode", "none"); string cwd; JsonNode project = null;
            if (mode == "existing") {
                var id = body.S("project_id");
                if (id.Length == 0) throw new CodexError("请选择已有项目");
                project = (await rpc.Call("project/read", Obj(("projectId", id))))["project"]!;
                cwd = CodexProjects.ProjectDirectory(project, body.S("cwd"));
            } else if (mode == "new") {
                var paths = body.A("roots").Select(root => CodexProjects.DirectoryPath(root.S("path"))).Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
                if (paths.Length == 0) paths = new[] { CodexProjects.DirectoryPath(body.S("cwd")) };
                if (paths.Length > 16) throw new CodexError("一个项目最多选择 16 个工作目录");
                cwd = body.S("cwd").Length > 0 ? CodexProjects.DirectoryPath(body.S("cwd")) : paths[0];
                if (!paths.Contains(cwd, StringComparer.OrdinalIgnoreCase)) throw new CodexError("默认工作目录必须是项目中的文件夹");
                var name = body.S("project_name").Trim();
                if (name.Length is < 1 or > 120) throw new CodexError("项目名称需为 1～120 个字符");
                var roots = new JsonArray(paths.Select(path => (JsonNode)Obj(("path", path))).ToArray());
                var signature = CodexProjects.RootSignature(roots);
                project = (await CodexProjects.List(rpc)).FirstOrDefault(row => CodexProjects.RootSignature(row.A("roots")) == signature);
                project ??= (await rpc.Call("project/create", Obj(("name", name), ("roots", roots),
                    ("idempotencyKey", "chuckie-project-" + Hash(signature)))))["project"]!;
            } else if (mode == "none") {
                cwd = Path.Combine(settings.S("working_directory"), DateTimeOffset.UtcNow.ToOffset(TimeSpan.FromHours(8)).ToString("yyyy-MM-dd"), "chat-" + Guid.NewGuid().ToString("N")[..12]);
                Directory.CreateDirectory(cwd);
            } else throw new CodexError("请选择已有项目、新项目或无项目");
            var request = Obj(("cwd", cwd), ("projectId", project?.S("id")), ("ephemeral", false));
            if (project != null) request["runtimeWorkspaceRoots"] = new JsonArray(project.A("roots").Select(root => (JsonNode)JsonValue.Create(root.S("path"))).ToArray());
            var owner = NewRuntime(); JsonObject result;
            try { await owner.Initialize(); result = await owner.Call("thread/start", request); }
            catch { await owner.DisposeAsync(); throw; }
            var thread = result["thread"]!;
            lock (gate) sessionRpcs[thread.S("id")] = owner;
            var title = body.S("title", "手机 Codex 对话");
            await owner.Call("thread/name/set", Obj(("threadId", thread.S("id")), ("name", title)));
            lock (gate) loaded[thread.S("id")] = result;
            thread["name"] = title;
            titles.Register(thread.S("id"), title, body.B("auto_title"));
            var session = Session(thread); session["project_name"] = project?.S("name");
            // Empty threads may not have a durable rollout yet. Keep the initial
            // subscription until their first input is accepted instead of losing it.
            if (File.Exists(thread.S("path"))) await ReleaseSessionCore(thread.S("id"));
            await NotifyDesktop(new[] { thread.S("id") });
            return Obj(("session", session));
        } finally { settingsLock.Release(); }
    }
    private async Task<JsonObject> CompactSession(string id, string key) {
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) throw new CodexError("压缩需要唯一请求标识");
        await settingsLock.WaitAsync();
        try {
            lock (gate) {
                var existing = runs.FirstOrDefault(entry => entry.Value.S("key") == key);
                if (existing.Value != null) {
                    if (existing.Value.S("session_id") != id || existing.Value.S("kind") != "compact") throw new CodexError("请求标识内容不一致", 409);
                    return Obj(("run_id", existing.Key), ("replayed", true));
                }
                if (active.ContainsKey(id)) throw new CodexError("请先等待当前任务结束再压缩上下文", 409);
            }
            var thread = (await ReadThread(id, false))["thread"]!;
            if (thread["status"].S("type") == "active" || CodexRollout.IsRunning(home, RolloutPath(thread))) throw new CodexError("此会话正在运行，请结束任务后再压缩", 409);
            var run = "codex_" + Guid.NewGuid().ToString("N");
            JsonObject resumed;
            try { resumed = await (await EnsureRuntime(id)).Call("thread/resume", await ResumeSessionRequest(id)); }
            catch (CodexError error) when (error.Message.Contains("already has an active writer", StringComparison.OrdinalIgnoreCase)) {
                await ReleaseSessionCore(id);
                var path = thread.S("path");
                var offset = new FileInfo(path).Length;
                lock (gate) {
                    active[id] = run;
                    runs[run] = Obj(("run_id", run), ("key", key), ("kind", "compact"), ("owner", "desktop"), ("session_id", id), ("status", "started"), ("output", "")); Persist();
                }
                try { await CodexDesktopSync.Compact(id); }
                catch (CodexError desktopError) {
                    lock (gate) { runs[run]!["status"] = desktopError.Status == 504 ? "acceptance_unknown" : "failed"; runs[run]!["error"] = desktopError.Message; active.Remove(id); Persist(); }
                    if (desktopError.Status == 504) _ = ObserveDesktopCompact(id, run, path, offset);
                    return Obj(("run_id", run), ("status", runs[run].S("status")));
                }
                _ = ObserveDesktopCompact(id, run, path, offset);
                return Obj(("run_id", run), ("status", "started"));
            }
            lock (gate) {
                loaded[id] = resumed; active[id] = run;
                runs[run] = Obj(("run_id", run), ("key", key), ("kind", "compact"), ("session_id", id), ("status", "started"), ("output", ""), ("model", resumed.S("model")), ("provider", resumed.S("modelProvider"))); Persist();
            }
            try { await Runtime(id).Call("thread/compact/start", Obj(("threadId", id))); }
            catch (Exception error) {
                lock (gate) { runs[run]!["status"] = error is CodexError { Status: 400 or 409 } ? "failed" : "acceptance_unknown"; active.Remove(id); Persist(); }
                _ = ReleaseSession(id); throw;
            }
            return Obj(("run_id", run), ("status", runs[run].S("status")));
        } finally { settingsLock.Release(); }
    }
    private async Task ObserveDesktopCompact(string id, string run, string path, long offset) {
        var deadline = DateTime.UtcNow.AddMinutes(15);
        try {
            while (DateTime.UtcNow < deadline) {
                var result = CodexRollout.CompactResult(home, path, offset);
                if (result.Length > 0) {
                    lock (gate) { runs[run]!["status"] = result; runs[run]!["output"] = result == "completed" ? "上下文已压缩" : ""; active.Remove(id); contexts.Remove(id); Persist(); }
                    Emit(run, result == "completed" ? "run.completed" : "run.failed"); return;
                }
                await Task.Delay(1000);
            }
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
        lock (gate) { runs[run]!["status"] = "acceptance_unknown"; runs[run]!["error"] = "压缩结果尚未确认，请核对会话历史；不会自动重发"; active.Remove(id); Persist(); }
        Emit(run, "run.failed");
    }
    private async Task<JsonObject> Config() => (await rpc.Call("config/read", Obj(("includeLayers", false))))["config"]!.AsObject();
    private async Task<JsonObject> Models() {
        var config = await Config(); var catalog = await rpc.Call("model/list", new());
        var options = catalog.A("data").Where(m => !m.B("hidden")).ToArray();
        var current = config.S("model_provider", "openai"); var rows = new JsonArray();
        var known = config["model_providers"]?.DeepClone() as JsonObject ?? new();
        known["openai"] ??= Obj(("name", "OpenAI / ChatGPT"));
        foreach (var entry in known) {
            var choices = new[] { providers[entry.Key].S("model"), entry.Key == current ? config.S("model") : "" }.Concat(options.Select(m => m.S("model", m.S("id")))).Where(x => x.Length > 0).Distinct();
            rows.Add(Obj(("slug", entry.Key), ("name", entry.Value.S("name", entry.Key)), ("authenticated", true), ("is_current", entry.Key == current),
                ("is_user_defined", entry.Key != "openai"), ("models", new JsonArray(choices.Select(x => (JsonNode)(options.FirstOrDefault(m => m.S("model", m.S("id")) == x)?.DeepClone() ?? Obj(("id", x), ("model", x)))).ToArray()))));
        }
        return Obj(("providers", rows), ("provider", current), ("model", config.S("model")), ("reasoning_effort", config["model_reasoning_effort"]), ("service_tier", config["service_tier"]));
    }
    private async Task EditConfig(params (string Key, object Value)[] edits) {
        lock (gate) if (active.Count > 0) throw new CodexError("请先停止手机 Codex 任务再修改全局配置", 409);
        var backup = Path.Combine(home, "backups", "chuckie-helper", DateTime.UtcNow.ToString("yyyyMMdd-HHmmss") + "-" + Guid.NewGuid().ToString("N")[..6]);
        Directory.CreateDirectory(backup);
        if (File.Exists(Path.Combine(home, "config.toml"))) File.Copy(Path.Combine(home, "config.toml"), Path.Combine(backup, "config.toml"));
        var changes = new JsonArray(edits.Select(edit => (JsonNode)Obj(("keyPath", edit.Key), ("value", edit.Value), ("mergeStrategy", "upsert"))).ToArray());
        await rpc.Call("config/batchWrite", Obj(("edits", changes), ("reloadUserConfig", true)));
    }
    private int[] OwnedProcessIds() {
        lock (gate) return sessionRpcs.Values.Where(client => client.Running).Select(client => client.ProcessId)
            .Concat(rpc?.Running == true ? new[] { rpc.ProcessId } : Array.Empty<int>())
            .Concat(loginRpc?.Running == true ? new[] { loginRpc.ProcessId } : Array.Empty<int>()).Distinct().ToArray();
    }
    private bool DesktopBusy() => CodexAccountStore.DesktopBusy(OwnedProcessIds());
    private async Task SwitchAccount(string id) {
        await settingsLock.WaitAsync();
        try {
            accounts.ValidateWorkspaceSwitch(id);
            if (accounts.List().S("current") == id) return;
            // Verify the target before stopping processes; failed validation leaves the
            // running desktop and its current credentials intact.
            var targetAuth = accounts.WorkspaceAuth(id);
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(25));
            var target = await CodexWorkspaceUsage.Read(settings.S("executable"), folder, targetAuth, timeout.Token);
            accounts.RecoverWorkspaceAuth(id, targetAuth, target.Auth);
            var desktop = CodexDesktopRestart.Capture(OwnedProcessIds());
            JsonObject previous = null;
            try {
                lock (gate) {
                    foreach (var run in active.Values) { runs[run]!["status"] = "interrupted"; runs[run]!["error"] = "切换工作空间已停止旧进程"; }
                    active.Clear(); approvals.Clear(); Persist();
                }
                await rpc.DisposeAsync();
                foreach (var session in sessionRpcs.Keys.ToArray()) await ReleaseSessionCore(session);
                await desktop.Stop();
                previous = accounts.CurrentAuth();
                accounts.Use(id); await Launch();
                var current = await rpc.Call("account/read", Obj(("refreshToken", false)));
                if (current["account"] == null) throw new CodexError("目标登录已失效，请重新授权", 409);
                var routed = current["workspaceRouting"].S("chatgptAccountId");
                if (routed.Length > 0 && routed != CurrentWorkspace()) throw new CodexError("Codex 后台工作空间不匹配，已取消切换", 409);
                accounts.Capture(accounts.CurrentAuth());
            } catch {
                if (rpc != null) await rpc.DisposeAsync();
                if (previous?.Count > 0) accounts.Restore(previous);
                await Launch(); throw;
            } finally { desktop.Restart(); }
        } finally { settingsLock.Release(); }
    }
    private async Task<JsonObject> BeginLogin(JsonObject body) {
        await settingsLock.WaitAsync();
        try { return await BeginLoginCore(body); }
        finally { settingsLock.Release(); }
    }
    private async Task<JsonObject> BeginLoginCore(JsonObject body) {
        if (loginRpc?.Running == true && login != null && !login.B("completed")) return login.DeepClone().AsObject();
        if (loginRpc != null) { await loginRpc.DisposeAsync(); loginRpc = null; }
        loginFolder = Path.Combine(folder, "login-capture", Guid.NewGuid().ToString("N")); Directory.CreateDirectory(loginFolder);
        loginName = body.S("name"); login = Obj(("completed", false), ("success", false));
        var overrides = new List<string> { "cli_auth_credentials_store=\"file\"" };
        if (body.S("workspace_id").Length > 0) overrides.Add("forced_chatgpt_workspace_id=" + JsonValue.Create(body.S("workspace_id"))!.ToJsonString());
        var loginRecord = login;
        loginRpc = new CodexRpc(settings.S("executable"), loginFolder, new Dictionary<string, string>(), message => {
            if (message.S("method") == "account/login/completed") lock (gate) { loginRecord["completed"] = true; loginRecord["success"] = message["params"].B("success"); loginRecord["error"] = message["params"].S("error"); }
            return Task.CompletedTask;
        }, overrides.ToArray());
        try {
            await loginRpc.Initialize();
            var result = await loginRpc.Call("account/login/start", Obj(("type", "chatgptDeviceCode")));
            lock (gate) foreach (var entry in result) login[entry.Key] = entry.Value?.DeepClone();
            return login.DeepClone().AsObject();
        } catch { await loginRpc.DisposeAsync(); loginRpc = null; login = null; throw; }
    }
    private async Task<JsonObject> LoginStatus() {
        await settingsLock.WaitAsync();
        try { return await LoginStatusCore(); }
        finally { settingsLock.Release(); }
    }
    private async Task<JsonObject> LoginStatusCore() {
        if (login == null) return Obj(("completed", true), ("success", false));
        if (login.B("completed") && login.B("success") && !login.B("saved")) {
            var auth = Read(Path.Combine(loginFolder, "auth.json"));
            login["account_id"] = accounts.Capture(auth, loginName); login["saved"] = true;
            await loginRpc.DisposeAsync(); loginRpc = null;
            // Only the dedicated capture directory is removed; shared CODEX_HOME stays intact.
            if (Path.GetFullPath(loginFolder).StartsWith(Path.GetFullPath(Path.Combine(folder, "login-capture")) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) Directory.Delete(loginFolder, true);
        }
        return login.DeepClone().AsObject();
    }
    private async Task<JsonObject> StartRun(JsonObject body, string key) {
        await settingsLock.WaitAsync();
        try { return await StartRunCore(body, key); }
        finally { settingsLock.Release(); }
    }
    private async Task<JsonObject> StartRunCore(JsonObject body, string key) {
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) throw new CodexError("缺少任务幂等标识");
        if (body.S("account_id").Length > 0 && body.S("account_id") != accounts.List().S("current")) throw new CodexError("电脑登录身份已变化，请刷新后发送", 409);
        var fingerprint = Hash(body.ToJsonString()); var session = body.S("session_id"); var run = "codex_" + Guid.NewGuid().ToString("N");
        if (await handoff.SessionBusy(session)) throw new CodexError("此会话仍由升级前的连接执行，请等待当前任务结束；本次消息未写入。", 409, "session_writer_busy");
        lock (gate) {
            foreach (var entry in runs) if (entry.Value.S("key") == key) {
                if (entry.Value.S("fingerprint") != fingerprint) throw new CodexError("相同任务标识内容不一致", 409);
                return Obj(("run_id", entry.Key), ("status", entry.Value.S("status")), ("replayed", true));
            }
            if (active.ContainsKey(session)) throw new CodexError("此会话已有手机任务在运行", 409);
            runs[run] = Obj(("run_id", run), ("session_id", session), ("key", key), ("fingerprint", fingerprint), ("status", "submitting"), ("phase", "preparing"),
                ("output", ""), ("created_at", DateTimeOffset.UtcNow.ToUnixTimeSeconds()), ("account_id", accounts.List().S("current")));
            active[session] = run; Persist();
        }
        try {
            bool desktopOwner = false;
            string steeringTurn = "";
            var questionReply = body.S("question_reply_id");
            if (questionReply.Length > 0) {
                var thread = (await ReadThread(session, true))["thread"]!;
                var pending = CodexSessionDetails.Read(home, RolloutPath(thread))["question"];
                if (pending.S("request_id") != questionReply) throw new CodexError("该问题已回答或过期，请刷新会话", 409);
                steeringTurn = thread.A("turns").LastOrDefault(turn => turn.S("status") == "inProgress").S("id");
            }
            JsonObject resumed; lock (gate) loaded.TryGetValue(session, out resumed);
            if (resumed == null) {
                JsonObject request;
                try { request = await ResumeSessionRequest(session); }
                catch (CodexError error) { throw TranslateBusyThread(session, error); }
                try { resumed = await (await EnsureRuntime(session)).Call("thread/resume", request); }
                catch (CodexError error) when (error.Message.Contains("already has an active writer", StringComparison.OrdinalIgnoreCase)) {
                    await ReleaseSessionCore(session);
                    var thread = (await ReadThread(session, false))["thread"]!;
                    if (questionReply.Length == 0 && (thread["status"].S("type") == "active" || CodexRollout.IsRunning(home, RolloutPath(thread)))) throw new CodexError("此会话正在另一端执行任务，本次消息未写入；可等待结束，或选择中断并接管。", 409, "session_writer_busy");
                    desktopOwner = true;
                    resumed = Obj(("model", CodexRollout.LastModel(home, RolloutPath(thread))), ("modelProvider", thread.S("modelProvider")),
                        ("collaborationMode", CodexRollout.LastSettings(home, RolloutPath(thread))["collaborationMode"]));
                }
                catch (CodexError error) { throw TranslateBusyThread(session, error); }
            }
            lock (gate) { if (!desktopOwner) loaded[session] = resumed; runs[run]!["model"] = resumed.S("model"); runs[run]!["provider"] = resumed.S("modelProvider"); }
            var inputs = CodexAttachmentInput.Build(body.S("input", "请查看附件"), body.A("attachment_paths"), settings.S("attachments"), session);
            JsonObject selection = null;
            lock (gate) if (sessionOverrides.TryGetValue(session, out var chosen)) selection = chosen.DeepClone().AsObject();
            var turnRequest = Obj(("threadId", session), ("input", inputs), ("clientUserMessageId", key));
            if (selection != null) {
                var catalog = await rpc.Call("model/list", new());
                var detail = catalog.A("data").FirstOrDefault(row => row.S("model", row.S("id")) == selection.S("model")) as JsonObject ?? new();
                CodexModelSettings.ApplyTurn(turnRequest, selection, detail, resumed["collaborationMode"] as JsonObject);
            }
            lock (gate) { runs[run]!["status"] = "started"; runs[run]!["phase"] = "dispatching"; Persist(); }
            var context = Obj(("chuckie-attachments", Obj(("kind", "application"), ("value", "生成供手机下载的文件时保存到：" + body.S("outbox") + "。输出目录按需创建，实际写入文件时再创建所需父目录。回复中每个文件使用独立一行 MEDIA:绝对路径。"))));
            turnRequest["additionalContext"] = context;
            JsonObject result;
            if (desktopOwner) {
                lock (gate) { runs[run]!["owner"] = "desktop"; Persist(); }
                if (steeringTurn.Length > 0) {
                    await CodexDesktopSync.SteerTurn(session, inputs, key);
                    result = Obj(("turn", Obj(("id", steeringTurn))));
                } else { turnRequest.Remove("threadId"); result = (await CodexDesktopSync.StartTurn(session, turnRequest))["result"] as JsonObject ?? new(); }
            } else {
                try { result = await Runtime(session).Call("turn/start", turnRequest); }
                catch (CodexError error) { throw TranslateBusyThread(session, error); }
            }
            lock (gate) runs[run]!["turn_id"] = result["turn"].S("id"); Persist();
            if (desktopOwner) _ = ObserveDesktopRun(session, run, key);
            try { if (titles.Claim(session)) _ = GenerateTitle(session, body.S("input")); }
            catch (IOException) { /* Title persistence must not change an accepted chat result. */ }
            return Obj(("run_id", run), ("status", runs[run].S("status")), ("replayed", false));
        } catch (Exception error) {
            lock (gate) {
                if (runs[run].S("status") is not ("completed" or "failed" or "interrupted")) runs[run]!["status"] = runs[run].S("phase") == "preparing" || error is CodexError { Delivery: "rejected" } ? "failed" : "acceptance_unknown";
                runs[run]!["error"] = error is CodexError ? error.Message : "连接中断，请查看会话历史核对原任务";
                if (error is CodexError known) runs[run]!["error_code"] = known.Code;
                active.Remove(session); Persist();
            }
            if (runs[run].S("status") == "failed") _ = ReleaseSession(session);
            if (runs[run].S("phase") == "preparing" && error is CodexError preparation)
                throw new CodexError(preparation.Message, preparation.Status, preparation.Code, "rejected");
            throw;
        }
    }
    private async Task GenerateTitle(string session, string input) {
        try {
            var title = await titles.Generate(input);
            await settingsLock.WaitAsync();
            try {
                var thread = (await ReadThread(session, false))["thread"];
                if (!titles.CanApply(session, thread.S("name"))) { titles.Finish(session, false); return; }
                await rpc.Call("thread/name/set", Obj(("threadId", session), ("name", title)));
                titles.Finish(session, true);
            } finally { settingsLock.Release(); }
            await NotifyDesktop(new[] { session });
        } catch (Exception error) when (error is CodexError or IOException or InvalidOperationException or OperationCanceledException) {
            try { titles.Finish(session, false); } catch (IOException) { }
        }
    }
    private static IEnumerable<JsonNode> DesktopTurns(JsonObject state) {
        foreach (var turn in state.A("turns")) yield return turn;
        if (state["turnHistory"]?["history"]?["entitiesByKey"] is JsonObject entities) foreach (var entry in entities) yield return entry.Value;
    }
    private async Task ObserveDesktopRun(string session, string run, string key) {
        using var timeout = new CancellationTokenSource(TimeSpan.FromHours(2));
        var seenTools = new Dictionary<string, string>();
        var assistantMessages = new CodexAssistantMessageStream();
        try {
            await foreach (var snapshot in CodexDesktopSync.Follow(session, timeout.Token)) {
                string turnId; lock (gate) turnId = runs[run].S("turn_id");
                var turn = DesktopTurns(snapshot).LastOrDefault(t => (turnId.Length > 0 && t.S("turnId") == turnId) || t["params"].S("clientUserMessageId") == key);
                if (turn == null) continue;
                var text = string.Join("\n\n", turn.A("items").Where(item => item.S("type") == "agentMessage").Select(item => item.S("text")));
                lock (gate) { runs[run]!["output"] = text; runs[run]!["turn_id"] = turn.S("turnId"); }
                foreach (var change in assistantMessages.Update(turn)) {
                    var kind = change.S("event"); change.Remove("event");
                    Emit(run, kind, change.Select(pair => (pair.Key, (object)pair.Value)).ToArray());
                }
                foreach (var item in turn.A("items")) {
                    var kind = item.S("type");
                    if (kind is "userMessage" or "agentMessage" or "reasoning" or "contextCompaction" or "userInputResponse") continue;
                    var completed = item.B("completed") || item.S("status") is "completed" or "failed" or "declined";
                    var phase = completed ? "tool.completed" : "tool.started"; var id = item.S("id");
                    if (seenTools.GetValueOrDefault(id) == phase) continue; seenTools[id] = phase;
                    var preview = Regex.Replace(item.S("command", item.S("query", item.S("arguments"))), "\\s+", " "); if (preview.Length > 180) preview = preview[..180];
                    Emit(run, phase, ("tool", item.S("tool", item.S("name", kind))), ("preview", preview), ("error", item.S("status") is "failed" or "declined"));
                }
                var request = snapshot.A("requests").FirstOrDefault(r => r["params"].S("turnId") == turn.S("turnId") && r.S("method") is "item/tool/requestUserInput" or "item/commandExecution/requestApproval" or "item/fileChange/requestApproval" or "item/permissions/requestApproval");
                if (request != null) {
                    var requestKey = request["id"]!.ToJsonString(); var p = request["params"];
                    var approval = Obj(("request_id", requestKey), ("kind", request.S("method").EndsWith("requestUserInput") ? "user_input" : "approval"),
                        ("description", p.S("reason", p.S("command", "Codex 请求你的确认"))), ("command", p.S("command")), ("questions", p.A("questions")));
                    bool changed; lock (gate) { changed = runs[run]?["approval"].S("request_id") != requestKey; approvals[requestKey] = request.DeepClone().AsObject(); runs[run]!["approval"] = approval.DeepClone(); }
                    if (changed) Emit(run, "approval.request", approval.Select(x => (x.Key, (object)x.Value)).ToArray());
                } else lock (gate) runs[run]!.AsObject().Remove("approval");
                var usage = snapshot["latestTokenUsageInfo"];
                if (usage?["last"] != null) lock (gate) contexts[session] = Obj(("available", true), ("tokens", usage["last"].L("totalTokens")), ("limit", usage["modelContextWindow"]), ("estimated", false));
                var status = turn.S("status");
                if (status is "completed" or "failed" or "interrupted") {
                    lock (gate) { runs[run]!["status"] = status; runs[run]!["error"] = turn["error"].S("message"); runs[run]!.AsObject().Remove("approval"); active.Remove(session); Persist(); }
                    Emit(run, status == "completed" ? "run.completed" : "run.failed"); return;
                }
            }
        } catch (Exception error) when (error is IOException or OperationCanceledException or InvalidOperationException or System.Text.Json.JsonException) { }
        lock (gate) { runs[run]!["status"] = "acceptance_unknown"; runs[run]!["error"] = "桌面会话进度连接中断，请核对会话历史；不会自动重发"; active.Remove(session); Persist(); }
        Emit(run, "run.failed");
    }
    private static long MessageId(string id) => Convert.ToInt64(Hash(id)[..14], 16) + 1;
    private async Task<JsonObject> Route(HttpContext context, JsonObject body) {
        var method = context.Request.Method; var path = context.Request.Path.Value!.Trim('/'); var p = path.Split('/');
        if (path == "health") return Obj(("agent", "codex"), ("implementation", "dotnet-v2"), ("state_version", 10), ("ready", rpc?.Running == true), ("cli_pid", rpc?.ProcessId));
        if (path == "capabilities") return Obj(("agent", "codex"), ("sessions", true), ("runs", true), ("model_options", true), ("attachments", true), ("attachment_steering", true), ("message_items", true), ("session_takeover", true), ("title_model", true), ("state_version", 10));
        if (path == "title-model") return method == "GET" ? titles.PublicConfig() : titles.Save(body);
        if (path == "title-model/test") return Obj(("title", await titles.Generate("修复手机会话的消息顺序与状态显示")));
        if (path == "title-model/generate") return Obj(("title", await titles.Generate(body.S("input"), context.RequestAborted)));
        if (path == "title-model/models") return await titles.Models(body, context.RequestAborted);
        if (path == "model-options") return await Models();
        if (path == "projects" && method == "GET") return Obj(("data", await CodexProjects.List(rpc)));
        if (path == "usage") {
            var current = accounts.List().S("current");
            if (current.Length == 0) return (await CodexWorkspaceUsage.Read(settings.S("executable"), folder, accounts.CurrentAuth(), context.RequestAborted)).Usage;
            var quota = await WorkspaceUsage(current, false, context.RequestAborted);
            if (!quota.B("available")) throw new CodexError(quota.S("error"), 502);
            return quota["usage"]!.DeepClone().AsObject();
        }
        if (p.Length == 3 && p[0] == "workspaces" && p[2] == "usage" && method == "GET") return await WorkspaceUsage(p[1], context.Request.Query["refresh"].ToString() is "1" or "true", context.RequestAborted);
        if (p.Length == 3 && p[0] == "workspaces" && p[2] == "rate-limit-resets" && method == "GET") return await WorkspaceResetCredits(p[1], context.RequestAborted);
        if (p.Length == 4 && p[0] == "workspaces" && p[2] == "rate-limit-resets" && p[3] == "consume" && method == "POST")
            return await ConsumeWorkspaceResetCredit(p[1], body.S("creditId"), body.B("useNextAvailable"), context.Request.Headers["Idempotency-Key"].ToString(), context.RequestAborted);
        if (path == "accounts") { var result = accounts.List(); result["desktop_running"] = DesktopBusy(); return result; }
        if (path == "accounts/import") { accounts.Capture(accounts.CurrentAuth()); return Obj(("saved", true)); }
        if (path == "accounts/login") return await BeginLogin(body);
        if (path == "accounts/login-status") return await LoginStatus();
        if (p.Length == 3 && p[0] == "accounts" && p[2] == "remove") { accounts.Remove(p[1]); return Obj(("removed", true)); }
        if (p.Length == 3 && p[0] is "accounts" or "workspaces" && p[2] == "use") { await SwitchAccount(p[1]); return Obj(("current", p[1])); }
        if (path == "workspaces") {
            if (method == "POST") return await BeginLogin(body);
            var result = accounts.List(); var email = result["current_identity"].S("email");
            result["data"] = new JsonArray(result.A("data").Where(row => email.Length == 0 || row.S("email") == email).Select(row => row!.DeepClone()).ToArray()); return result;
        }
        if (path == "providers") {
            var config = await Config();
            if (method == "GET") {
                var rows = new JsonArray();
                foreach (var entry in config["model_providers"]?.AsObject() ?? new JsonObject()) rows.Add(Obj(("id", entry.Key), ("name", entry.Value.S("name", entry.Key)),
                    ("base_url", entry.Value.S("base_url")), ("model", providers[entry.Key].S("model", entry.Key == config.S("model_provider") ? config.S("model") : "")),
                    ("api_mode", "codex_responses"), ("has_api_key", providers[entry.Key].S("api_key").Length > 0 || entry.Value.S("env_key").Length > 0 || entry.Value.S("experimental_bearer_token").Length > 0)));
                return Obj(("endpoints", rows));
            }
            var id = body.S("id", "mobile_" + Guid.NewGuid().ToString("N")[..8]);
            if (!Regex.IsMatch(id, "^[a-zA-Z0-9_-]{1,80}$") || !Uri.TryCreate(body.S("base_url"), UriKind.Absolute, out var url) || url.Scheme is not ("http" or "https") || url.UserInfo.Length > 0) throw new CodexError("Provider 标识或 API 地址无效");
            if (body.S("api_mode") is not ("" or "codex_responses")) throw new CodexError("Codex Provider 需要 Responses API 兼容端点");
            await settingsLock.WaitAsync();
            try {
                var value = config["model_providers"]?[id]?.DeepClone() as JsonObject ?? new();
                value["name"] = body.S("name"); value["base_url"] = body.S("base_url"); value["wire_api"] = "responses";
                var saved = providers[id]?.DeepClone() as JsonObject ?? new(); saved["model"] = body.S("model");
                if (body.S("api_key").Length > 0) { saved["api_key"] = body.S("api_key"); value.Remove("experimental_bearer_token"); value["env_key"] = "CHUCKIE_CODEX_" + id.ToUpperInvariant() + "_KEY"; value["requires_openai_auth"] = false; }
                await EditConfig(("model_providers." + id, value)); providers[id] = saved; Atomic(Path.Combine(folder, "providers.json"), providers);
                if (body.S("api_key").Length > 0) { await rpc.DisposeAsync(); await Launch(); }
                return Obj(("saved", true));
            } finally { settingsLock.Release(); }
        }
        if (path == "default-model") {
            if (method == "POST") {
                await settingsLock.WaitAsync();
                try {
                    var selected = CodexModelSettings.Validate(body, await rpc.Call("model/list", new()));
                    var edits = new List<(string Key, object Value)> { ("model_provider", selected.S("modelProvider")), ("model", selected.S("model")) };
                    if (selected.ContainsKey("reasoningEffort")) edits.Add(("model_reasoning_effort", selected["reasoningEffort"]));
                    if (selected.ContainsKey("serviceTier")) edits.Add(("service_tier", selected["serviceTier"]));
                    await EditConfig(edits.ToArray()); return Obj(("saved", true));
                }
                finally { settingsLock.Release(); }
            }
            return await Models();
        }
        if (p[0] == "sessions") {
            if (p.Length == 3 && p[2] == "queue") return await Queue(p[1], method, body, context.RequestAborted);
            if (p.Length == 3 && p[2] == "takeover" && method == "POST") return await TakeoverSession(p[1], body, context.RequestAborted);
            if (p.Length == 3 && p[2] == "active-run" && method == "GET") return await FindActiveRun(p[1], context.RequestAborted);
            if (p.Length == 3 && p[2] == "desktop-activity" && method == "GET") return await CodexDesktopActivity.Read(p[1], context.RequestAborted);
            if (p.Length == 1 && method == "GET") {
                var request = Obj(("limit", 50), ("sortKey", "updated_at"), ("sourceKinds", new JsonArray("cli", "vscode", "appServer")));
                if (context.Request.Query["cursor"].Count > 0) request["cursor"] = context.Request.Query["cursor"].ToString();
                var result = await rpc.Call("thread/list", request);
                _ = NotifyDesktop(result.A("data").Where(thread => thread.S("originator") == "chuckie_helper_mobile").Select(thread => thread.S("id")).ToArray());
                var threads = result.A("data").Where(t => t.S("id").Length > 0).GroupBy(t => t.S("id")).Select(group => group.First()).ToArray();
                var previews = LatestSessionPreview.Read(Path.Combine(home, "thread_history_1.sqlite"), threads.Select(thread => thread.S("id")), true);
                var unique = threads.Select(thread => {
                    var row = Session(thread);
                    if (previews.TryGetValue(thread.S("id"), out var text)) { row["latest_user_message"] = text; row["preview"] = text; }
                    var continuation = CodexPreviewRollout.Read(home, thread.S("id"), thread.S("path"));
                    if (continuation.Length > 0) { row["latest_user_message"] = continuation; row["preview"] = continuation; }
                    return (JsonNode)row;
                });
                return Obj(("data", new JsonArray(unique.ToArray())), ("next_cursor", result["nextCursor"]), ("has_more", result.S("nextCursor").Length > 0));
            }
            if (p.Length == 1) {
                return await CreateSession(body);
            }
            var session = p[1];
            if (p.Length == 3 && p[2] == "compact" && method == "POST") return await CompactSession(session, context.Request.Headers["Idempotency-Key"].ToString());
            if (p.Length == 3 && p[2] == "context") {
                var thread = (await ReadThread(session, false))["thread"]!;
                var detail = CodexSessionDetails.Read(home, RolloutPath(thread));
                lock (gate) if (contexts.TryGetValue(session, out var current)) detail["context"] = current.DeepClone();
                detail["control"] = SessionControl(thread);
                detail["title"] = thread["name"]?.DeepClone();
                return detail;
            }
            if (p.Length == 2 && method == "GET") {
                var result = await ReadThread(session, false);
                var thread = result["thread"]!; var model = CodexRollout.LastModel(home, RolloutPath(thread)); var provider = thread.S("modelProvider");
                lock (gate) if (loaded.TryGetValue(session, out var current)) { model = current.S("model", model); provider = current.S("modelProvider", provider); }
                lock (gate) if (sessionOverrides.TryGetValue(session, out var selected)) { model = selected.S("model", model); provider = selected.S("modelProvider", provider); }
                var info = Session(thread); info["model"] = model; info["provider"] = provider;
                var tuning = CodexRollout.LastSettings(home, RolloutPath(thread));
                lock (gate) if (sessionOverrides.TryGetValue(session, out var chosen)) tuning = chosen.DeepClone().AsObject();
                info["reasoning_effort"] = tuning["reasoningEffort"]?.DeepClone(); info["service_tier"] = tuning["serviceTier"]?.DeepClone();
                var detail = CodexSessionDetails.Read(home, RolloutPath(thread)); info["context"] = detail["context"]?.DeepClone(); info["question"] = detail["question"]?.DeepClone();
                info["control"] = SessionControl(thread);
                info["title_generation"] = titles.State(session);
                return Obj(("session", info));
            }
            if (p.Length == 2 && method == "PATCH") {
                await settingsLock.WaitAsync();
                try { await rpc.Call("thread/name/set", Obj(("threadId", session), ("name", body.S("title")))); titles.Manual(session); }
                finally { settingsLock.Release(); }
                return Obj(("session", Obj(("id", session), ("title", body.S("title")))));
            }
            if (p.Length == 3 && p[2] == "delete") {
                await settingsLock.WaitAsync();
                try {
                    lock (gate) if (active.ContainsKey(session)) throw new CodexError("运行中的会话不可删除", 409);
                    await ReleaseSessionCore(session);
                    await rpc.Call("thread/delete", Obj(("threadId", session)));
                    lock (gate) { sessionOverrides.Remove(session); PersistModels(); }
                    await CodexDesktopSync.NotifyRemoved(new[] { session });
                    return Obj(("deleted", true));
                } finally { settingsLock.Release(); }
            }
            if (p.Length == 3 && p[2] == "model") {
                await settingsLock.WaitAsync();
                try {
                    lock (gate) if (active.ContainsKey(session)) throw new CodexError("请先停止任务再切换模型", 409);
                    var selection = CodexModelSettings.Validate(body, await rpc.Call("model/list", new()));
                    var request = await ResumeSessionRequest(session); CodexModelSettings.ApplyResume(request, selection);
                    try {
                        var resumed = await (await EnsureRuntime(session)).Call("thread/resume", request);
                        lock (gate) loaded[session] = resumed;
                        if (File.Exists(resumed["thread"].S("path"))) await ReleaseSessionCore(session);
                    }
                    catch (CodexError error) when (error.Message.Contains("already has an active writer", StringComparison.OrdinalIgnoreCase)) {
                        await ReleaseSessionCore(session);
                        var thread = (await ReadThread(session, false))["thread"]!;
                        if (thread["status"].S("type") == "active" || CodexRollout.IsRunning(home, RolloutPath(thread))) throw new CodexError("请等待当前任务结束后修改模型设置", 409);
                        if (thread.S("modelProvider") != selection.S("modelProvider")) throw new CodexError("该会话由桌面端管理，请在桌面切换 Provider；手机可修改模型、思考程度和速度", 409);
                    }
                    lock (gate) { sessionOverrides[session] = selection; PersistModels(); }
                    return Obj(("model", selection["model"]), ("provider", selection["modelProvider"]), ("reasoning_effort", selection["reasoningEffort"]), ("service_tier", selection["serviceTier"]));
                } finally { settingsLock.Release(); }
            }
            if (p.Length == 3 && p[2] == "messages") {
                var result = await ReadThread(session, true); var rows = new JsonArray();
                var times = CodexMessageTimes.Read(home, session);
                var source = CodexRolloutMessageTimes.Read(home, RolloutPath(result["thread"]));
                long position = 0;
                foreach (var turn in result["thread"].A("turns")) foreach (var item in turn.A("items")) {
                    var kind = item.S("type");
                    if(kind is not ("userMessage" or "agentMessage"))continue;
                    var role=kind=="userMessage"?"user":"assistant";
                    var text=role=="user"?string.Join('\n',item.A("content").Where(c=>c.S("type")=="text").Select(c=>c.S("text"))):item.S("text");
                    object timestamp=source.Resolve(item.S("id"),role,text,ref position,out var nativeTime)?nativeTime:
                        times.TryGetValue(item.S("id"),out var exact)?exact:null;
                    rows.Add(Obj(("id",MessageId(item.S("id"))),("role",role),("timestamp",timestamp),("content",text),("phase",role=="assistant"?item.S("phase"):null)));
                }
                return Obj(("data", rows));
            }
        }
        if (p[0] == "runs") {
            if (path == "runs/lookup" && method == "GET") {
                var key = context.Request.Query["key"].ToString();
                if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) throw new CodexError("无效的请求标识");
                string pendingId; lock (gate) pendingId = runs.FirstOrDefault(entry => entry.Value.S("key") == key).Key;
                if (pendingId != null && handoff.Contains(pendingId)) {
                    var live = await handoff.Read(pendingId, context.RequestAborted);
                    return Obj(("found", true), ("run_id", pendingId), ("status", live.S("status")), ("session_id", live.S("session_id")));
                }
                lock (gate) {
                    var found = runs.FirstOrDefault(entry => entry.Value.S("key") == key);
                    return found.Value == null ? Obj(("found", false)) : Obj(("found", true), ("run_id", found.Key), ("status", found.Value.S("status")), ("session_id", found.Value.S("session_id")));
                }
            }
            if (p.Length == 1) return await StartRun(body, context.Request.Headers["Idempotency-Key"].ToString());
            var state = await ReconcileRun(p[1]);
            if (p.Length == 2) {
                if (state.S("owner") == "desktop" && state.S("status") == "started" && state.S("turn_id").Length > 0) {
                    var observed = await CodexDesktopActivity.Read(state.S("session_id"), context.RequestAborted);
                    if (observed.B("available") && (observed.S("activity_id") != state.S("turn_id") || !observed.B("running"))) {
                        // The tracked desktop turn ended or a newer one replaced it. Never keep its old timer alive.
                        var known = observed["turn_statuses"].S(state.S("turn_id"));
                        // A still-running tracked turn only means the snapshot ordering has not
                        // caught up yet; treat a missing status as unknown only when idle.
                        var finished = known is "completed" or "failed" or "interrupted" ? known
                            : known == "inProgress" || observed.B("running") ? "started" : "acceptance_unknown";
                        lock (gate) {
                            if (runs[p[1]].S("status") == "started") {
                                runs[p[1]]!["status"] = finished;
                                if (finished == "acceptance_unknown") runs[p[1]]!["error"] = "桌面已进入另一轮任务，原轮次结果不在当前快照中，请查看会话历史核对";
                                if (active.GetValueOrDefault(state.S("session_id")) == p[1]) active.Remove(state.S("session_id"));
                                Persist();
                            }
                            state = runs[p[1]]!.DeepClone().AsObject();
                        }
                    }
                }
                state["runtime"] = Obj(("model", state.S("model")), ("provider", state.S("provider"))); return state;
            }
            if (p[2] == "stop") {
                if (state.S("status") == "started" && state.S("owner") == "desktop") await CodexDesktopSync.StopCompact(state.S("session_id"));
                else if (state.S("turn_id").Length > 0 && state.S("status") == "started") await Runtime(state.S("session_id")).Call("turn/interrupt", Obj(("threadId", state.S("session_id")), ("turnId", state.S("turn_id"))));
                return Obj(("stop_requested", state.S("status") == "started"), ("stopped", state.S("status") is not ("started" or "submitting")));
            }
            if (p[2] == "approval") {
                var key = body.S("request_id"); JsonObject request;
                lock (gate) {
                    if (runs[p[1]].A("answered_approvals").Any(value => value?.ToString() == key)) return Obj(("accepted", true), ("replayed", true));
                    if (!approvals.TryGetValue(key, out request) || request["params"].S("threadId") != state.S("session_id")) throw new CodexError("审批已过期", 409);
                    if (!replyingApprovals.Add(key)) throw new CodexError("正在发送该回答，请稍候", 409);
                }
                try {
                JsonObject answer = request.S("method").EndsWith("requestUserInput") ? Obj(("answers", body["answers"] ?? new JsonObject()))
                    : request.S("method") == "item/permissions/requestApproval" ? Obj(("permissions", body.S("choice") == "once" ? request["params"]?["permissions"] ?? new JsonObject() : new JsonObject()), ("scope", "turn"))
                    : Obj(("decision", body.S("choice") == "once" ? "accept" : "decline"));
                if (state.S("owner") == "desktop") await CodexDesktopSync.Reply(state.S("session_id"), request, answer);
                else await Runtime(state.S("session_id")).Write(Obj(("id", request["id"]), ("result", answer)));
                lock (gate) {
                    approvals.Remove(key);
                    var current = runs[p[1]]!.AsObject();
                    if (current["approval"].S("request_id") == key) current.Remove("approval");
                    var answered = current.A("answered_approvals"); answered.Add(key); current["answered_approvals"] = answered.DeepClone(); Persist();
                }
                return Obj(("accepted", true));
                } finally { lock (gate) replyingApprovals.Remove(key); }
            }
            if (p[2] == "steer") {
                await settingsLock.WaitAsync();
                try {
                    state = await ReconcileRun(p[1]);
                    if (state.S("status") != "started" || state.S("kind") == "compact") throw new CodexError("原任务已结束或连接已释放，本次插话未写入。请刷新后作为新消息发送。", 409, "run_stale");
                    var key = context.Request.Headers["Idempotency-Key"].ToString();
                    if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) throw new CodexError("插话需要唯一请求标识");
                    if (body.S("session_id").Length > 0 && body.S("session_id") != state.S("session_id")) throw new CodexError("附件不属于当前任务的会话");
                    var input = CodexAttachmentInput.Build(body.S("input"), body.A("attachment_paths"), settings.S("attachments"), state.S("session_id"));
                    var fingerprint = Hash(body.ToJsonString());
                    JsonObject record;
                    lock (gate) {
                        var requests = runs[p[1]]!["steering_requests"] as JsonObject;
                        if (requests == null) runs[p[1]]!["steering_requests"] = requests = new();
                        if (requests[key] != null) {
                            if (requests[key].S("fingerprint") != fingerprint) throw new CodexError("相同插话标识内容不一致", 409);
                            if (requests[key].S("status") != "accepted") throw new CodexError("上次插话结果未确认，请核对历史；不会再次发送", 409);
                            return Obj(("accepted", true), ("replayed", true));
                        }
                        requests[key] = record = Obj(("fingerprint", fingerprint), ("status", "dispatching")); Persist();
                    }
                    try {
                        if (state.S("owner") == "desktop") await CodexDesktopSync.SteerTurn(state.S("session_id"), input, key);
                        else try { await Runtime(state.S("session_id")).Call("turn/steer", Obj(("threadId", state.S("session_id")), ("expectedTurnId", state.S("turn_id")), ("input", input), ("clientUserMessageId", key))); }
                        catch (CodexError error) {
                            if (error.Message.Contains("thread not found", StringComparison.OrdinalIgnoreCase)) {
                                lock (gate) { CodexRunRecovery.Recover(runs[p[1]]!.AsObject(), false); active.Remove(state.S("session_id")); loaded.Remove(state.S("session_id")); Persist(); }
                                throw new CodexError("原任务连接已释放，本次插话未写入。请刷新后作为新消息发送。", 409, "run_stale");
                            }
                            throw TranslateBusyThread(state.S("session_id"), error);
                        }
                        lock (gate) { record["status"] = "accepted"; Persist(); }
                    } catch (Exception error) { lock (gate) { record["status"] = error is CodexError { Delivery: "rejected" } ? "rejected" : "unconfirmed"; Persist(); } throw; }
                    return Obj(("accepted", true));
                } finally { settingsLock.Release(); }
            }
        }
        throw new CodexError("不支持的 Codex 操作", 404);
    }
    private async Task Serve(HttpContext context) {
        var supplied = Encoding.UTF8.GetBytes(context.Request.Headers.Authorization.ToString()); var expected = Encoding.UTF8.GetBytes("Bearer " + settings.S("token"));
        if (!CryptographicOperations.FixedTimeEquals(supplied, expected)) { context.Response.StatusCode = 401; return; }
        try {
            var path = context.Request.Path.Value!.Trim('/');
            if (await handoff.Forward(context, path)) return;
            // Account reads and login capture use isolated credentials; they must not
            // stop or depend on a task owned by the current inference app-server.
            if (path != "health" && path != "usage" && !path.StartsWith("accounts", StringComparison.Ordinal) && !path.StartsWith("workspaces", StringComparison.Ordinal)) await EnsureConnection();
            if (Regex.IsMatch(path, "^runs/[a-zA-Z0-9_-]+/events$")) { await Stream(context, path.Split('/')[1]); return; }
            if (context.Request.ContentLength > 2 * 1024 * 1024) throw new CodexError("请求过大", 413);
            var body = context.Request.ContentLength is > 0 ? (await JsonNode.ParseAsync(context.Request.Body, cancellationToken: context.RequestAborted))!.AsObject() : new JsonObject();
            var value = await Route(context, body); await context.Response.WriteAsJsonAsync(value, context.RequestAborted);
        } catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested) { }
        catch (CodexError error) when (!context.Response.HasStarted) { context.Response.StatusCode = error.Status; await context.Response.WriteAsJsonAsync(new { message = error.Message, code = error.Code, delivery = error.Delivery }); }
        catch (Exception) when (!context.Response.HasStarted) { context.Response.StatusCode = 503; await context.Response.WriteAsJsonAsync(new { message = "本机 Codex 处理失败；任务不会自动重发" }); }
    }
    private async Task Stream(HttpContext context, string run) {
        lock (gate) if (!runs.ContainsKey(run)) throw new CodexError("任务不存在", 404);
        context.Response.ContentType = "text/event-stream"; context.Response.Headers.CacheControl = "no-store";
        long.TryParse(context.Request.Headers["Last-Event-ID"], out var last);
        while (!context.RequestAborted.IsCancellationRequested) {
            List<JsonObject> batch; bool done;
            lock (gate) { batch = events.GetValueOrDefault(run)?.Where(e => e.L("seq") > last).Select(e => e.DeepClone().AsObject()).ToList() ?? new(); done = runs[run].S("status") is "completed" or "failed" or "interrupted" or "acceptance_unknown"; }
            foreach (var value in batch) { await context.Response.WriteAsync("id: " + value.L("seq") + "\ndata: " + value.ToJsonString() + "\n\n", context.RequestAborted); last = value.L("seq"); }
            await context.Response.WriteAsync(": keepalive\n\n", context.RequestAborted); await context.Response.Body.FlushAsync(context.RequestAborted);
            if (done) return; await Task.Delay(1000, context.RequestAborted);
        }
    }
    public static async Task RunAsync(string config) {
        await using var agent = new CodexAgent(config); await agent.Launch(); agent.accounts.List();
        var builder = WebApplication.CreateSlimBuilder(new WebApplicationOptions { Args = Array.Empty<string>(), ContentRootPath = Path.GetDirectoryName(config) });
        builder.Logging.ClearProviders(); builder.WebHost.UseKestrel().UseUrls("http://127.0.0.1:0");
        var app = builder.Build(); app.Run(agent.Serve); await app.StartAsync();
        var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
        Atomic(Path.Combine(Path.GetDirectoryName(config)!, "status.json"), Obj(("port", new Uri(address).Port), ("pid", Environment.ProcessId)));
        _ = agent.handoff.Retire();
        await app.WaitForShutdownAsync();
    }
    public async ValueTask DisposeAsync() {
        handoff.Dispose();
        foreach (var id in sessionRpcs.Keys.ToArray()) await ReleaseSessionCore(id);
        if (loginRpc != null) await loginRpc.DisposeAsync(); if (rpc != null) await rpc.DisposeAsync();
    }
}
