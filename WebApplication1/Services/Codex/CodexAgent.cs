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

internal sealed class CodexAgent : IAsyncDisposable
{
    private readonly JsonObject settings;
    private readonly string folder, home, journal;
    private readonly CodexAccountStore accounts;
    private readonly object gate = new();
    private readonly SemaphoreSlim settingsLock = new(1, 1);
    private JsonObject providers, runs;
    private CodexRpc rpc;
    private readonly Dictionary<string, JsonObject> loaded = new();
    private readonly Dictionary<string, string> active = new();
    private readonly Dictionary<string, List<JsonObject>> events = new();
    private readonly Dictionary<string, JsonObject> approvals = new();
    private readonly HashSet<string> replyingApprovals = new();
    private CodexRpc loginRpc;
    private string loginFolder, loginName;
    private JsonObject login;
    private readonly Dictionary<string, JsonObject> workspaceUsage = new();
    private readonly HashSet<string> desktopAnnounced = new();
    private readonly Dictionary<string, JsonObject> sessionOverrides = new();
    private readonly Dictionary<string, JsonObject> contexts = new();
    private async Task ReleaseSession(string id) {
        await settingsLock.WaitAsync();
        try {
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
        await rpc.Call("thread/unsubscribe", Obj(("threadId", id)));
        lock (gate) loaded.Remove(id);
    }
    private async Task<JsonObject> ResumeSessionRequest(string id) {
        var request = Obj(("threadId", id));
        var thread = (await rpc.Call("thread/read", Obj(("threadId", id), ("includeTurns", false))))["thread"]!;
        if (thread.S("projectId").Length > 0) {
            var project = (await rpc.Call("project/read", Obj(("projectId", thread.S("projectId")))))["project"]!;
            request["runtimeWorkspaceRoots"] = new JsonArray(project.A("roots").Select(root => (JsonNode)JsonValue.Create(root.S("path"))).ToArray());
        }
        lock (gate) if (sessionOverrides.TryGetValue(id, out var selected)) { request["model"] = selected["model"]?.DeepClone(); request["modelProvider"] = selected["modelProvider"]?.DeepClone(); }
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
            if (!refresh && workspaceUsage.TryGetValue(id, out var cached) && cached.L("checked_at") > DateTimeOffset.UtcNow.ToUnixTimeSeconds() - 30) return cached.DeepClone().AsObject();
            JsonObject result;
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(20));
            try {
                JsonObject usage;
                if (accounts.List().S("current") == id) usage = await rpc.Call("account/rateLimits/read", new(), timeout.Token);
                else {
                    var probe = await CodexWorkspaceUsage.Read(settings.S("executable"), folder, auth, timeout.Token);
                    usage = probe.Usage; accounts.RecoverWorkspaceAuth(id, auth, probe.Auth);
                }
                result = Obj(("workspace_id", id), ("available", true), ("usage", usage), ("checked_at", DateTimeOffset.UtcNow.ToUnixTimeSeconds()));
            } catch (OperationCanceledException) when (!ct.IsCancellationRequested) {
                result = Obj(("workspace_id", id), ("available", false), ("error", "查询超时，请重试"));
            } catch (Exception error) when (error is CodexError or IOException) {
                result = Obj(("workspace_id", id), ("available", false), ("error", "暂时无法读取用量，请刷新；登录失效时请重新授权"));
            }
            if (result.B("available")) workspaceUsage[id] = result.DeepClone().AsObject();
            return result;
        } finally { settingsLock.Release(); }
    }

    private CodexAgent(string config) {
        settings = Read(config); folder = Path.GetDirectoryName(config)!; home = settings.S("home");
        journal = Path.Combine(folder, "runs.json");
        providers = Read(Path.Combine(folder, "providers.json")); runs = Read(journal);
        accounts = new CodexAccountStore(home, settings.S("account_store", Path.Combine(Path.GetDirectoryName(home)!, ".codex-switch")));
    }
    private async Task Launch() {
        var environment = new Dictionary<string, string>();
        foreach (var entry in providers) if (entry.Value.S("api_key").Length > 0) environment["CHUCKIE_CODEX_" + entry.Key.ToUpperInvariant() + "_KEY"] = entry.Value.S("api_key");
        rpc = new CodexRpc(settings.S("executable"), home, environment, Notification);
        await rpc.Initialize(); loaded.Clear();
    }
    private async Task EnsureConnection() {
        if (rpc?.Running == true) return;
        await settingsLock.WaitAsync();
        try {
            if (rpc?.Running == true) return;
            lock (gate) {
                foreach (var run in active.Values) {
                    runs[run]!["status"] = "acceptance_unknown";
                    runs[run]!["error"] = "连接中断，请查看会话历史核对原任务；不会自动重发";
                }
                active.Clear(); approvals.Clear(); Persist();
            }
            if (rpc != null) await rpc.DisposeAsync();
            await Launch();
        } finally { settingsLock.Release(); }
    }
    private void Persist() { lock (gate) Atomic(journal, runs); }
    private void Emit(string run, string kind, params (string Key, object Value)[] values) {
        lock (gate) {
            var state = runs[run]!; var seq = state.L("seq") + 1; state["seq"] = seq;
            var value = Obj(values); value["event"] = kind; value["seq"] = seq; value["run_id"] = run;
            if (!events.TryGetValue(run, out var list)) events[run] = list = new();
            list.Add(value); if (list.Count > 256) list.RemoveRange(0, list.Count - 256);
        }
    }
    private async Task Notification(JsonObject message) {
        var method = message.S("method"); var p = message["params"] as JsonObject ?? new JsonObject();
        if (method == "thread/tokenUsage/updated") {
            var usage = p["tokenUsage"];
            lock (gate) contexts[p.S("threadId")] = Obj(("available", true), ("tokens", usage?["last"].L("totalTokens")), ("limit", usage?["modelContextWindow"]), ("estimated", false));
        }
        string run; lock (gate) active.TryGetValue(p.S("threadId"), out run);
        if (message.ContainsKey("id")) {
            if (run == null) { await rpc.Write(Obj(("id", message["id"]), ("error", Obj(("code", -32601), ("message", "No mobile turn owns this request"))))); return; }
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
            Emit(run, "message.delta", ("delta", delta));
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
            _ = ReleaseAndNotify(p.S("threadId"));
        } else if (method == "serverRequest/resolved") { lock (gate) runs[run]!.AsObject().Remove("approval"); }
    }
    private JsonObject Session(JsonNode thread) {
        var result = Obj(("id", thread.S("id")), ("title", thread.S("name", thread.S("preview", "未命名会话"))),
        ("preview", thread.S("preview")), ("cwd", thread.S("cwd")), ("source", thread.S("source", "codex")),
        ("model", thread.S("model")), ("project_id", thread?["projectId"]), ("message_count", thread.A("turns").Count), ("status", thread?["status"]));
        if (thread["status"].S("type") != "active" && CodexRollout.IsRunning(home, thread.S("path"))) result["status"] = Obj(("type", "active"), ("activeFlags", new JsonArray()));
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
            var result = await rpc.Call("thread/start", request); var thread = result["thread"]!;
            var title = body.S("title", "手机 Codex 对话");
            await rpc.Call("thread/name/set", Obj(("threadId", thread.S("id")), ("name", title)));
            lock (gate) loaded[thread.S("id")] = result;
            thread["name"] = title;
            var session = Session(thread); session["project_name"] = project?.S("name");
            await ReleaseSessionCore(thread.S("id"));
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
            var thread = (await rpc.Call("thread/read", Obj(("threadId", id), ("includeTurns", false))))["thread"]!;
            if (thread["status"].S("type") == "active" || CodexRollout.IsRunning(home, thread.S("path"))) throw new CodexError("此会话正在运行，请结束任务后再压缩", 409);
            var run = "codex_" + Guid.NewGuid().ToString("N");
            JsonObject resumed;
            try { resumed = await rpc.Call("thread/resume", await ResumeSessionRequest(id)); }
            catch (CodexError error) when (error.Message.Contains("already has an active writer", StringComparison.OrdinalIgnoreCase)) {
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
            try { await rpc.Call("thread/compact/start", Obj(("threadId", id))); }
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
        var options = catalog.A("data").Select(m => m.S("model", m.S("id"))).Where(x => x.Length > 0).ToList();
        var current = config.S("model_provider", "openai"); var rows = new JsonArray();
        var known = config["model_providers"]?.DeepClone() as JsonObject ?? new();
        known["openai"] ??= Obj(("name", "OpenAI / ChatGPT"));
        foreach (var entry in known) {
            var choices = new[] { providers[entry.Key].S("model"), entry.Key == current ? config.S("model") : "" }.Concat(options).Where(x => x.Length > 0).Distinct();
            rows.Add(Obj(("slug", entry.Key), ("name", entry.Value.S("name", entry.Key)), ("authenticated", true), ("is_current", entry.Key == current),
                ("is_user_defined", entry.Key != "openai"), ("models", new JsonArray(choices.Select(x => (JsonNode)JsonValue.Create(x)).ToArray()))));
        }
        return Obj(("providers", rows), ("provider", current), ("model", config.S("model")));
    }
    private async Task EditConfig(params (string Key, object Value)[] edits) {
        lock (gate) if (active.Count > 0) throw new CodexError("请先停止手机 Codex 任务再修改全局配置", 409);
        var backup = Path.Combine(home, "backups", "chuckie-helper", DateTime.UtcNow.ToString("yyyyMMdd-HHmmss") + "-" + Guid.NewGuid().ToString("N")[..6]);
        Directory.CreateDirectory(backup);
        if (File.Exists(Path.Combine(home, "config.toml"))) File.Copy(Path.Combine(home, "config.toml"), Path.Combine(backup, "config.toml"));
        var changes = new JsonArray(edits.Select(edit => (JsonNode)Obj(("keyPath", edit.Key), ("value", edit.Value), ("mergeStrategy", "upsert"))).ToArray());
        await rpc.Call("config/batchWrite", Obj(("edits", changes), ("reloadUserConfig", true)));
    }
    private bool DesktopBusy() => CodexAccountStore.DesktopBusy(loginRpc?.Running == true ? new[] { rpc.ProcessId, loginRpc.ProcessId } : new[] { rpc.ProcessId });
    private async Task SwitchAccount(string id) {
        await settingsLock.WaitAsync();
        try {
            accounts.ValidateWorkspaceSwitch(id);
            if (accounts.List().S("current") == id) return;
            var desktop = CodexDesktopRestart.Capture(loginRpc?.Running == true ? new[] { rpc.ProcessId, loginRpc.ProcessId } : new[] { rpc.ProcessId });
            JsonObject previous = null;
            try {
                lock (gate) {
                    foreach (var run in active.Values) { runs[run]!["status"] = "interrupted"; runs[run]!["error"] = "切换工作空间已停止旧进程"; }
                    active.Clear(); approvals.Clear(); Persist();
                }
                await rpc.DisposeAsync();
                await desktop.Stop();
                previous = accounts.CurrentAuth();
                accounts.Use(id); await Launch();
                var current = await rpc.Call("account/read", Obj(("refreshToken", true)));
                if (current["account"] == null) throw new CodexError("目标登录已失效，请重新授权", 409);
                accounts.Capture(accounts.CurrentAuth());
            } catch {
                if (rpc != null) await rpc.DisposeAsync();
                if (previous?.Count > 0) accounts.Restore(previous);
                await Launch(); throw;
            } finally { desktop.Restart(); }
        } finally { settingsLock.Release(); }
    }
    private async Task<JsonObject> BeginLogin(JsonObject body) {
        if (loginRpc != null && login != null && !login.B("completed")) throw new CodexError("已有登录正在等待授权", 409);
        loginFolder = Path.Combine(folder, "login-capture", Guid.NewGuid().ToString("N")); Directory.CreateDirectory(loginFolder);
        loginName = body.S("name"); login = Obj(("completed", false), ("success", false));
        var overrides = new List<string> { "cli_auth_credentials_store=\"file\"" };
        if (body.S("workspace_id").Length > 0) overrides.Add("forced_chatgpt_workspace_id=" + JsonValue.Create(body.S("workspace_id"))!.ToJsonString());
        loginRpc = new CodexRpc(settings.S("executable"), loginFolder, new Dictionary<string, string>(), message => {
            if (message.S("method") == "account/login/completed") lock (gate) { login["completed"] = true; login["success"] = message["params"].B("success"); login["error"] = message["params"].S("error"); }
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
                var thread = (await rpc.Call("thread/read", Obj(("threadId", session), ("includeTurns", true))))["thread"]!;
                var pending = CodexSessionDetails.Read(home, thread.S("path"))["question"];
                if (pending.S("request_id") != questionReply) throw new CodexError("该问题已回答或过期，请刷新会话", 409);
                steeringTurn = thread.A("turns").LastOrDefault(turn => turn.S("status") == "inProgress").S("id");
            }
            JsonObject resumed; lock (gate) loaded.TryGetValue(session, out resumed);
            if (resumed == null) {
                var request = await ResumeSessionRequest(session);
                try { resumed = await rpc.Call("thread/resume", request); }
                catch (CodexError error) when (error.Message.Contains("already has an active writer", StringComparison.OrdinalIgnoreCase)) {
                    var thread = (await rpc.Call("thread/read", Obj(("threadId", session), ("includeTurns", false))))["thread"]!;
                    if (questionReply.Length == 0 && (thread["status"].S("type") == "active" || CodexRollout.IsRunning(home, thread.S("path")))) throw new CodexError("此会话正在另一端执行任务，请等待结束后发送", 409);
                    desktopOwner = true;
                    resumed = Obj(("model", CodexRollout.LastModel(home, thread.S("path"))), ("modelProvider", thread.S("modelProvider")));
                }
            }
            lock (gate) { if (!desktopOwner) loaded[session] = resumed; runs[run]!["model"] = resumed.S("model"); runs[run]!["provider"] = resumed.S("modelProvider"); }
            var inputs = CodexAttachmentInput.Build(body.S("input", "请查看附件"), body.A("attachment_paths"), settings.S("attachments"), session);
            lock (gate) { runs[run]!["status"] = "started"; runs[run]!["phase"] = "dispatching"; Persist(); }
            var context = Obj(("chuckie-attachments", Obj(("kind", "application"), ("value", "生成供手机下载的文件时保存到：" + body.S("outbox") + "。回复中每个文件使用独立一行 MEDIA:绝对路径。"))));
            JsonObject result;
            if (desktopOwner) {
                lock (gate) { runs[run]!["owner"] = "desktop"; Persist(); }
                if (steeringTurn.Length > 0) {
                    await CodexDesktopSync.SteerTurn(session, inputs, key);
                    result = Obj(("turn", Obj(("id", steeringTurn))));
                } else result = (await CodexDesktopSync.StartTurn(session, Obj(("input", inputs), ("clientUserMessageId", key), ("additionalContext", context))))["result"] as JsonObject ?? new();
            } else result = await rpc.Call("turn/start", Obj(("threadId", session), ("input", inputs), ("clientUserMessageId", key), ("additionalContext", context)));
            lock (gate) runs[run]!["turn_id"] = result["turn"].S("id"); Persist();
            if (desktopOwner) _ = ObserveDesktopRun(session, run, key);
            return Obj(("run_id", run), ("status", runs[run].S("status")), ("replayed", false));
        } catch (Exception error) {
            lock (gate) {
                if (runs[run].S("status") is not ("completed" or "failed" or "interrupted")) runs[run]!["status"] = runs[run].S("phase") == "preparing" || error is CodexError { Status: 400 or 409 } ? "failed" : "acceptance_unknown";
                runs[run]!["error"] = error is CodexError ? error.Message : "连接中断，请查看会话历史核对原任务";
                active.Remove(session); Persist();
            }
            if (runs[run].S("status") == "failed") _ = ReleaseSession(session);
            throw;
        }
    }
    private static IEnumerable<JsonNode> DesktopTurns(JsonObject state) {
        foreach (var turn in state.A("turns")) yield return turn;
        if (state["turnHistory"]?["history"]?["entitiesByKey"] is JsonObject entities) foreach (var entry in entities) yield return entry.Value;
    }
    private async Task ObserveDesktopRun(string session, string run, string key) {
        using var timeout = new CancellationTokenSource(TimeSpan.FromHours(2));
        var seenTools = new Dictionary<string, string>(); var previousText = "";
        try {
            await foreach (var snapshot in CodexDesktopSync.Follow(session, timeout.Token)) {
                string turnId; lock (gate) turnId = runs[run].S("turn_id");
                var turn = DesktopTurns(snapshot).LastOrDefault(t => (turnId.Length > 0 && t.S("turnId") == turnId) || t["params"].S("clientUserMessageId") == key);
                if (turn == null) continue;
                var text = string.Join("\n\n", turn.A("items").Where(item => item.S("type") == "agentMessage").Select(item => item.S("text")));
                lock (gate) { runs[run]!["output"] = text; runs[run]!["turn_id"] = turn.S("turnId"); }
                if (text.StartsWith(previousText, StringComparison.Ordinal) && text.Length > previousText.Length) Emit(run, "message.delta", ("delta", text[previousText.Length..]));
                previousText = text;
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
        if (path == "health") return Obj(("agent", "codex"), ("implementation", "dotnet-v2"), ("ready", rpc?.Running == true), ("cli_pid", rpc?.ProcessId));
        if (path == "capabilities") return Obj(("agent", "codex"), ("sessions", true), ("runs", true), ("model_options", true), ("attachments", true), ("attachment_steering", true));
        if (path == "model-options") return await Models();
        if (path == "projects" && method == "GET") return Obj(("data", await CodexProjects.List(rpc)));
        if (path == "usage") return await rpc.Call("account/rateLimits/read", new());
        if (p.Length == 3 && p[0] == "workspaces" && p[2] == "usage" && method == "GET") return await WorkspaceUsage(p[1], context.Request.Query["refresh"] == "1", context.RequestAborted);
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
                try { await EditConfig(("model_provider", body.S("provider")), ("model", body.S("model"))); return Obj(("saved", true)); }
                finally { settingsLock.Release(); }
            }
            return await Models();
        }
        if (p[0] == "sessions") {
            if (p.Length == 1 && method == "GET") {
                var request = Obj(("limit", 50), ("sortKey", "updated_at"), ("sourceKinds", new JsonArray("cli", "vscode", "appServer")));
                if (context.Request.Query["cursor"].Count > 0) request["cursor"] = context.Request.Query["cursor"].ToString();
                var result = await rpc.Call("thread/list", request);
                _ = NotifyDesktop(result.A("data").Where(thread => thread.S("originator") == "chuckie_helper_mobile").Select(thread => thread.S("id")).ToArray());
                var unique = result.A("data").Where(t => t.S("id").Length > 0).GroupBy(t => t.S("id")).Select(group => (JsonNode)Session(group.First()));
                return Obj(("data", new JsonArray(unique.ToArray())), ("next_cursor", result["nextCursor"]), ("has_more", result.S("nextCursor").Length > 0));
            }
            if (p.Length == 1) {
                return await CreateSession(body);
            }
            var session = p[1];
            if (p.Length == 3 && p[2] == "compact" && method == "POST") return await CompactSession(session, context.Request.Headers["Idempotency-Key"].ToString());
            if (p.Length == 3 && p[2] == "context") {
                var thread = (await rpc.Call("thread/read", Obj(("threadId", session), ("includeTurns", false))))["thread"]!;
                var detail = CodexSessionDetails.Read(home, thread.S("path"));
                lock (gate) if (contexts.TryGetValue(session, out var current)) detail["context"] = current.DeepClone();
                return detail;
            }
            if (p.Length == 2 && method == "GET") {
                var result = await rpc.Call("thread/read", Obj(("threadId", session), ("includeTurns", false)));
                var thread = result["thread"]!; var model = CodexRollout.LastModel(home, thread.S("path")); var provider = thread.S("modelProvider");
                lock (gate) if (loaded.TryGetValue(session, out var current)) { model = current.S("model", model); provider = current.S("modelProvider", provider); }
                lock (gate) if (sessionOverrides.TryGetValue(session, out var selected)) { model = selected.S("model", model); provider = selected.S("modelProvider", provider); }
                var info = Session(thread); info["model"] = model; info["provider"] = provider;
                var detail = CodexSessionDetails.Read(home, thread.S("path")); info["context"] = detail["context"]?.DeepClone(); info["question"] = detail["question"]?.DeepClone();
                return Obj(("session", info));
            }
            if (p.Length == 2 && method == "PATCH") { await rpc.Call("thread/name/set", Obj(("threadId", session), ("name", body.S("title")))); return Obj(("session", Obj(("id", session), ("title", body.S("title"))))); }
            if (p.Length == 3 && p[2] == "delete") {
                await settingsLock.WaitAsync();
                try {
                    lock (gate) if (active.ContainsKey(session)) throw new CodexError("运行中的会话不可删除", 409);
                    await ReleaseSessionCore(session);
                    await rpc.Call("thread/delete", Obj(("threadId", session)));
                    lock (gate) sessionOverrides.Remove(session);
                    await CodexDesktopSync.NotifyRemoved(new[] { session });
                    return Obj(("deleted", true));
                } finally { settingsLock.Release(); }
            }
            if (p.Length == 3 && p[2] == "model") {
                await settingsLock.WaitAsync();
                try {
                    lock (gate) if (active.ContainsKey(session)) throw new CodexError("请先停止任务再切换模型", 409);
                    var request = await ResumeSessionRequest(session); request["model"] = body.S("model"); request["modelProvider"] = body.S("provider");
                    var result = await rpc.Call("thread/resume", request);
                    lock (gate) sessionOverrides[session] = Obj(("model", result.S("model")), ("modelProvider", result.S("modelProvider")));
                    await ReleaseSessionCore(session);
                    return Obj(("model", result["model"]), ("provider", result["modelProvider"]));
                } finally { settingsLock.Release(); }
            }
            if (p.Length == 3 && p[2] == "messages") {
                var result = await rpc.Call("thread/read", Obj(("threadId", session), ("includeTurns", true))); var rows = new JsonArray();
                var times = CodexMessageTimes.Read(home, session);
                foreach (var turn in result["thread"].A("turns")) foreach (var item in turn.A("items")) {
                    var kind = item.S("type");
                    var fallback = turn.L(kind == "userMessage" ? "startedAt" : "completedAt");
                    object timestamp = times.TryGetValue(item.S("id"), out var exact) ? exact : fallback > 0 ? fallback * 1000 : null;
                    if (kind == "userMessage") rows.Add(Obj(("id", MessageId(item.S("id"))), ("role", "user"), ("timestamp", timestamp), ("content", string.Join('\n', item.A("content").Where(c => c.S("type") == "text").Select(c => c.S("text"))))));
                    else if (kind == "agentMessage") rows.Add(Obj(("id", MessageId(item.S("id"))), ("role", "assistant"), ("timestamp", timestamp), ("content", item.S("text"))));
                }
                return Obj(("data", rows));
            }
        }
        if (p[0] == "runs") {
            if (path == "runs/lookup" && method == "GET") {
                var key = context.Request.Query["key"].ToString();
                if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) throw new CodexError("无效的请求标识");
                lock (gate) {
                    var found = runs.FirstOrDefault(entry => entry.Value.S("key") == key);
                    return found.Value == null ? Obj(("found", false)) : Obj(("found", true), ("run_id", found.Key), ("status", found.Value.S("status")), ("session_id", found.Value.S("session_id")));
                }
            }
            if (p.Length == 1) return await StartRun(body, context.Request.Headers["Idempotency-Key"].ToString());
            JsonObject state; lock (gate) state = runs[p[1]]?.DeepClone() as JsonObject ?? throw new CodexError("任务不存在", 404);
            if (p.Length == 2) { state["runtime"] = Obj(("model", state.S("model")), ("provider", state.S("provider"))); return state; }
            if (p[2] == "stop") {
                if (state.S("status") == "started" && state.S("owner") == "desktop") await CodexDesktopSync.StopCompact(state.S("session_id"));
                else if (state.S("turn_id").Length > 0 && state.S("status") == "started") await rpc.Call("turn/interrupt", Obj(("threadId", state.S("session_id")), ("turnId", state.S("turn_id"))));
                return Obj(("stopped", true));
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
                else await rpc.Write(Obj(("id", request["id"]), ("result", answer)));
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
                    if (state.S("status") != "started" || state.S("kind") == "compact") throw new CodexError("当前任务不能接收插话，请刷新状态", 409);
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
                        else await rpc.Call("turn/steer", Obj(("threadId", state.S("session_id")), ("expectedTurnId", state.S("turn_id")), ("input", input), ("clientUserMessageId", key)));
                        lock (gate) { record["status"] = "accepted"; Persist(); }
                    } catch { lock (gate) { record["status"] = "unconfirmed"; Persist(); } throw; }
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
            if (path != "health") await EnsureConnection();
            if (Regex.IsMatch(path, "^runs/[a-zA-Z0-9_-]+/events$")) { await Stream(context, path.Split('/')[1]); return; }
            if (context.Request.ContentLength > 2 * 1024 * 1024) throw new CodexError("请求过大", 413);
            var body = context.Request.ContentLength is > 0 ? (await JsonNode.ParseAsync(context.Request.Body, cancellationToken: context.RequestAborted))!.AsObject() : new JsonObject();
            var value = await Route(context, body); await context.Response.WriteAsJsonAsync(value, context.RequestAborted);
        } catch (OperationCanceledException) when (context.RequestAborted.IsCancellationRequested) { }
        catch (CodexError error) when (!context.Response.HasStarted) { context.Response.StatusCode = error.Status; await context.Response.WriteAsJsonAsync(new { message = error.Message }); }
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
        await app.WaitForShutdownAsync();
    }
    public async ValueTask DisposeAsync() { if (loginRpc != null) await loginRpc.DisposeAsync(); if (rpc != null) await rpc.DisposeAsync(); }
}
