using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

internal static class CodexWorkspaceUsage
{
    public static async Task<(JsonObject Usage, JsonObject Auth)> Read(string executable, string privateRoot, JsonObject auth, CancellationToken ct) {
        var root = Path.GetFullPath(Path.Combine(privateRoot, "usage-probes"));
        var probe = Path.Combine(root, Guid.NewGuid().ToString("N"));
        var identity = CodexAccountStore.Identity(auth);
        Directory.CreateDirectory(probe);
        try {
            Atomic(Path.Combine(probe, "auth.json"), auth);
            await using var client = new CodexRpc(executable, probe, new Dictionary<string, string>(), _ => Task.CompletedTask,
                "cli_auth_credentials_store=\"file\"", "forced_chatgpt_workspace_id=" + JsonValue.Create(identity.S("workspace_id"))!.ToJsonString());
            await client.Initialize(ct);
            var account = await client.Call("account/read", Obj(("refreshToken", false)), ct);
            if (account["account"] == null || account["workspaceRouting"].S("chatgptAccountId") != identity.S("workspace_id"))
                throw new CodexError("工作空间登录已失效，请重新登录", 409);
            var usage = await client.Call("account/rateLimits/read", new JsonObject(), ct);
            var refreshed = CodexJson.Read(Path.Combine(probe, "auth.json"));
            if (CodexAccountStore.Identity(refreshed).S("workspace_id") != identity.S("workspace_id"))
                throw new CodexError("用量查询的工作空间不匹配", 409);
            return (usage, refreshed);
        } finally {
            // Dedicated probe homes contain no conversations or shared configuration.
            if (Path.GetFullPath(probe).StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) && Directory.Exists(probe)) Directory.Delete(probe, true);
        }
    }
}
