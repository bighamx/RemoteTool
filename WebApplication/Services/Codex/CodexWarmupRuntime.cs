using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal sealed class CodexWarmupRuntime(CodexAccountStore accounts, string executable, string folder) : ICodexWarmupRuntime
{
    private string RequestHome(JsonObject attempt)
    {
        var key = attempt.S("request_key");
        if (!Regex.IsMatch(key, "^warmup-[a-f0-9]{32}$")) throw new CodexError("无效热身请求标识");
        return Path.Combine(folder, "workspace-warmup", "requests", key);
    }

    private JsonObject Auth(WarmupWorkspace workspace)
    {
        var auth = accounts.WarmupAuth(workspace.AccountId).DeepClone().AsObject();
        var identity = CodexAccountStore.Identity(auth);
        if (Hash(identity.S("subject") + "\n" + identity.S("workspace_id")) != workspace.Key)
            throw new CodexError("工作空间登录已变化", 409, "account_changed", "rejected");
        return auth;
    }

    public IReadOnlyList<WarmupWorkspace> Workspaces()
    {
        var result = new List<WarmupWorkspace>();
        foreach (var row in accounts.List().A("data"))
        {
            var id = row.S("id");
            string key;
            try
            {
                var identity = CodexAccountStore.Identity(accounts.WarmupAuth(id));
                key = Hash(identity.S("subject") + "\n" + identity.S("workspace_id"));
            }
            catch (CodexError) { key = Hash("invalid:" + id); }
            result.Add(new(key, id, row.S("workspace_id"), row.S("name")));
        }
        return result;
    }

    public async Task<JsonObject> Usage(WarmupWorkspace workspace, CancellationToken ct)
    {
        var original = Auth(workspace);
        var result = await CodexWorkspaceUsage.Read(executable, folder, original, ct);
        accounts.RecoverWorkspaceAuth(workspace.AccountId, original, result.Auth);
        return result.Usage;
    }

    private CodexRpc Open(string directory, string workspace, Func<JsonObject, Task> notification) =>
        new(executable, directory, new Dictionary<string, string>(), notification,
            ["cli_auth_credentials_store=\"file\"", "model_provider=\"openai\"",
            "approval_policy=\"never\"", "sandbox_mode=\"read-only\"",
            "forced_chatgpt_workspace_id=" + JsonValue.Create(workspace)!.ToJsonString()], directory);

    public async Task<JsonObject> Send(WarmupWorkspace workspace, JsonObject attempt, Action<JsonObject> checkpoint, CancellationToken ct)
    {
        var directory = RequestHome(attempt);
        IdentityDataDirectory.Ensure(directory);
        var original = Auth(workspace);
        Atomic(Path.Combine(directory, "auth.json"), original);
        var cwd = Path.Combine(directory, "work");
        Directory.CreateDirectory(cwd);
        var terminal = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        string threadId = null;
        CodexRpc client = null;
        async Task Notification(JsonObject message)
        {
            var parameters = message["params"];
            if (message.S("method") == "turn/completed" && parameters.S("threadId") == threadId && parameters?["turn"] is JsonObject turn)
                terminal.TrySetResult(turn.DeepClone().AsObject());
            if (message["id"] != null && client != null)
                await client.Write(Obj(("id", message["id"]), ("error", Obj(("code", -32000), ("message", "Warmup expects a short text response")))), ct);
        }
        await using var rpc = Open(directory, workspace.WorkspaceId, Notification);
        client = rpc;
        await rpc.Initialize(ct);
        // Usage already validated/refreshed this workspace's credentials. A forced
        // refresh can issue tokens for the login's default workspace instead.
        var login = await rpc.Call("account/read", Obj(("refreshToken", false)), ct);
        if (login["account"] == null) throw new CodexError("工作空间登录已失效", 409, "workspace_login_expired", "rejected");
        var refreshed = Read(Path.Combine(directory, "auth.json"));
        var identity = CodexAccountStore.Identity(refreshed);
        if (Hash(identity.S("subject") + "\n" + identity.S("workspace_id")) != workspace.Key)
            throw new CodexError("热身登录身份不匹配", 409, "account_changed", "rejected");
        accounts.RecoverWorkspaceAuth(workspace.AccountId, original, refreshed);
        var catalog = (await rpc.Call("model/list", new JsonObject(), ct)).A("data").OfType<JsonObject>()
            .Where(row => !row.B("hidden")).ToArray();
        var model = catalog.FirstOrDefault(row => row.B("isDefault")) ?? catalog.FirstOrDefault()
            ?? throw new CodexError("此工作空间没有可用的官方模型", 409, "model_unavailable", "rejected");
        var modelId = model.S("model", model.S("id"));
        var efforts = model.A("supportedReasoningEfforts").Select(row => row.S("reasoningEffort")).ToHashSet();
        var effort = new[] { "none", "minimal", "low" }.FirstOrDefault(efforts.Contains);
        var thread = await rpc.Call("thread/start", Obj(("model", modelId), ("modelProvider", "openai"),
            ("cwd", cwd), ("approvalPolicy", "never"), ("sandbox", "read-only")), ct);
        threadId = thread["thread"].S("id");
        if (threadId.Length == 0) throw new CodexError("未取得热身会话标识", 409, "thread_missing", "rejected");
        checkpoint(Obj(("thread_id", threadId), ("model", modelId)));
        var request = Obj(("threadId", threadId), ("model", modelId),
            ("input", new JsonArray(Obj(("type", "text"), ("text", "只回复 OK。")))), ("clientUserMessageId", attempt.S("request_key")));
        if (effort != null) request["effort"] = effort;
        Auth(workspace); // Recheck a login removed/replaced while the native client was preparing.
        checkpoint(Obj(("status", "sending"), ("dispatched_at", DateTimeOffset.UtcNow.ToUnixTimeSeconds()), ("message", "正在发送最小热身请求")));
        var accepted = await rpc.Call("turn/start", request, ct);
        checkpoint(Obj(("turn_id", accepted["turn"].S("id"))));
        return await terminal.Task.WaitAsync(TimeSpan.FromSeconds(90), ct);
    }

    public async Task<JsonObject> Reconcile(WarmupWorkspace workspace, JsonObject attempt, CancellationToken ct)
    {
        var directory = RequestHome(attempt);
        if (!Directory.Exists(directory) || attempt.S("thread_id").Length == 0) return new JsonObject();
        await using var rpc = Open(directory, workspace.WorkspaceId, _ => Task.CompletedTask);
        await rpc.Initialize(ct);
        var page = await rpc.Call("thread/turns/list", Obj(("threadId", attempt.S("thread_id")),
            ("limit", 2), ("itemsView", "full"), ("sortDirection", "desc")), ct);
        var turns = page.A("data").OfType<JsonObject>().ToArray();
        var expected = attempt.S("turn_id");
        var turn = expected.Length > 0 ? turns.SingleOrDefault(row => row.S("id") == expected)
            : turns.Length == 1 ? turns[0] : null;
        return turn?.DeepClone().AsObject() ?? new JsonObject();
    }
}
