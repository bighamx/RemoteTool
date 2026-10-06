using System.Text.Json.Nodes;
using System.Net;
using System.Net.Http.Headers;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

internal static class CodexWorkspaceUsage
{
    private static readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = false }) { Timeout = TimeSpan.FromSeconds(18) };
    internal static JsonObject Normalize(JsonObject data) {
        var limits = data["rate_limit"] as JsonObject ?? throw new CodexError("此工作空间未返回用量数据", 502);
        JsonNode Window(string name) {
            var value = limits[name];
            if (value == null) return null;
            if (!double.TryParse(value.S("used_percent"), System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.InvariantCulture, out var used) || !double.IsFinite(used))
                throw new CodexError("工作空间用量格式无效", 502);
            return Obj(("usedPercent", Math.Clamp(used, 0, 100)), ("windowDurationMins", value.L("limit_window_seconds") / 60), ("resetsAt", value["reset_at"]));
        }
        var normalized = Obj(("limitId", "codex"), ("planType", data.S("plan_type")), ("primary", Window("primary_window")), ("secondary", Window("secondary_window")), ("credits", data["credits"]));
        return Obj(("rateLimits", normalized), ("rateLimitsByLimitId", Obj(("codex", normalized))));
    }
    internal static void Verify(JsonObject identity, JsonObject usage) {
        static string Plan(string value) => value is "team" or "business" ? "team" : value;
        var actual = usage["rateLimits"].S("planType");
        if (actual.Length > 0 && Plan(actual) != Plan(identity.S("plan_type"))) throw new CodexError("返回的订阅与目标工作空间不一致，未执行切换", 409);
    }
    private static async Task<JsonObject> Direct(JsonObject auth, CancellationToken ct) {
        var identity = CodexAccountStore.Identity(auth);
        using var request = new HttpRequestMessage(HttpMethod.Get, "https://chatgpt.com/backend-api/wham/usage");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", auth["tokens"].S("access_token"));
        request.Headers.Add("ChatGPT-Account-Id", identity.S("workspace_id"));
        request.Headers.UserAgent.ParseAdd("ChuckieHelper/1.0");
        using var response = await http.SendAsync(request, ct);
        if (response.StatusCode == HttpStatusCode.Unauthorized) throw new CodexError("工作空间访问令牌需要更新", 401);
        if (!response.IsSuccessStatusCode) throw new CodexError(response.StatusCode == HttpStatusCode.TooManyRequests ? "用量查询过于频繁，请稍后重试" : "暂时无法读取该工作空间用量，请稍后重试", 502);
        var usage = Normalize(JsonNode.Parse(await response.Content.ReadAsStringAsync(ct))!.AsObject());
        Verify(identity, usage);
        return usage;
    }
    public static async Task<(JsonObject Usage, JsonObject Auth)> Read(string executable, string privateRoot, JsonObject auth, CancellationToken ct) {
        // Each request uses this workspace's own token AND routing header, never a live
        // app-server's cached account. A valid access token does not require forced refresh.
        try { return (await Direct(auth, ct), auth.DeepClone().AsObject()); }
        catch (CodexError error) when (error.Status == 401) { }
        var root = Path.GetFullPath(Path.Combine(privateRoot, "usage-probes"));
        var probe = Path.Combine(root, Guid.NewGuid().ToString("N"));
        var identity = CodexAccountStore.Identity(auth);
        Directory.CreateDirectory(probe);
        try {
            Atomic(Path.Combine(probe, "auth.json"), auth);
            await using var client = new CodexRpc(executable, probe, new Dictionary<string, string>(), _ => Task.CompletedTask,
                "cli_auth_credentials_store=\"file\"", "forced_chatgpt_workspace_id=" + JsonValue.Create(identity.S("workspace_id"))!.ToJsonString());
            await client.Initialize(ct);
            var account = await client.Call("account/read", Obj(("refreshToken", true)), ct);
            if (account["account"] == null)
                throw new CodexError("工作空间登录已失效，请重新登录", 409);
            var refreshed = CodexJson.Read(Path.Combine(probe, "auth.json"));
            if (CodexAccountStore.Identity(refreshed).S("workspace_id") != identity.S("workspace_id"))
                throw new CodexError("用量查询的工作空间不匹配", 409);
            return (await Direct(refreshed, ct), refreshed);
        } finally {
            // Dedicated probe homes contain no conversations or shared configuration.
            if (Path.GetFullPath(probe).StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) && Directory.Exists(probe)) Directory.Delete(probe, true);
        }
    }
}
