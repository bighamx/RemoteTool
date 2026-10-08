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
        JsonNode ResetCredits() {
            if (data["rate_limit_reset_credits"] is not JsonObject reset) return null;
            JsonNode Credit(JsonObject row) => Obj(("id", row["id"]), ("status", row["status"]), ("resetType", row["reset_type"]),
                ("grantedAt", row["granted_at"]), ("expiresAt", row["expires_at"]), ("title", row["title"]), ("description", row["description"]));
            var rows = reset["credits"] is JsonArray credits ? new JsonArray(credits.OfType<JsonObject>().Select(Credit).ToArray()) : null;
            return Obj(("availableCount", reset.L("available_count")), ("applicableCount", reset.L("applicable_available_count")), ("credits", rows));
        }
        var normalized = Obj(("limitId", "codex"), ("planType", data.S("plan_type")), ("primary", Window("primary_window")), ("secondary", Window("secondary_window")), ("credits", data["credits"]));
        return Obj(("rateLimits", normalized), ("rateLimitsByLimitId", Obj(("codex", normalized))), ("rateLimitResetCredits", ResetCredits()));
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
    private static async Task<(JsonObject Result, JsonObject Auth)> Probe(string executable, string privateRoot, JsonObject auth,
        Func<CodexRpc, JsonObject, CancellationToken, Task<JsonObject>> action, CancellationToken ct) {
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
            var result = await action(client, refreshed, ct);
            var finalAuth = CodexJson.Read(Path.Combine(probe, "auth.json"));
            if (CodexAccountStore.Identity(finalAuth).S("workspace_id") != identity.S("workspace_id"))
                throw new CodexError("用量查询的工作空间不匹配", 409);
            return (result, finalAuth);
        } finally {
            // Dedicated probe homes contain no conversations or shared configuration.
            if (Path.GetFullPath(probe).StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) && Directory.Exists(probe)) Directory.Delete(probe, true);
        }
    }
    private static Task<JsonObject> ReadRateLimits(CodexRpc client, CancellationToken ct) =>
        client.Call("account/rateLimits/read", Obj(("excludeResetCreditDetails", false)), ct);
    internal static void EnsureResetCreditAvailable(JsonObject usage, string creditId) {
        var summary = usage["rateLimitResetCredits"] as JsonObject;
        if (summary == null || summary.L("availableCount") < 1)
            throw new CodexError("当前工作空间没有可用的额度重置", 409);
        if (string.IsNullOrWhiteSpace(creditId)) return;
        if (creditId.Length > 512 || creditId.Any(char.IsControl))
            throw new CodexError("额度重置标识无效", 400);
        var found = summary["credits"] is JsonArray credits && credits.OfType<JsonObject>()
            .Any(row => row.S("id") == creditId && row.S("status") == "available");
        if (!found) throw new CodexError("所选额度重置已不可用，请刷新后重试", 409);
    }
    internal static void ValidateResetSelection(string creditId, bool useNextAvailable) {
        if (string.IsNullOrWhiteSpace(creditId) && !useNextAvailable)
            throw new CodexError("请选择要使用的额度重置", 400);
        if (!string.IsNullOrWhiteSpace(creditId) && useNextAvailable)
            throw new CodexError("额度重置选择冲突", 400);
    }
    public static async Task<(JsonObject Usage, JsonObject Auth)> ReadResetCredits(string executable, string privateRoot, JsonObject auth, CancellationToken ct) {
        var baseline = await Read(executable, privateRoot, auth, ct);
        if (baseline.Usage["rateLimitResetCredits"].L("availableCount") < 1 || baseline.Usage["rateLimitResetCredits"].L("applicableCount") < 1) return baseline;
        var identity = CodexAccountStore.Identity(baseline.Auth);
        using var detailTimeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        detailTimeout.CancelAfter(TimeSpan.FromSeconds(8));
        try {
            var probe = await Probe(executable, privateRoot, baseline.Auth, async (client, _, token) => {
                var usage = await ReadRateLimits(client, token);
                Verify(identity, usage);
                return usage;
            }, detailTimeout.Token);
            return (probe.Result, probe.Auth);
        } catch (OperationCanceledException) when (!ct.IsCancellationRequested) {
            return baseline;
        } catch (Exception error) when (error is CodexError or IOException or HttpRequestException) {
            // The usage endpoint still reports available/applicable reset counts when
            // this Codex build cannot fetch the optional detail list in an isolated home.
            return baseline;
        }
    }
    public static async Task<(JsonObject Result, JsonObject Auth)> ConsumeResetCredit(string executable, string privateRoot, JsonObject auth,
        string creditId, bool useNextAvailable, string idempotencyKey, CancellationToken ct) {
        if (string.IsNullOrWhiteSpace(idempotencyKey) || idempotencyKey.Length is < 16 or > 120 ||
            idempotencyKey.Any(value => !char.IsLetterOrDigit(value) && value is not '_' and not '-'))
            throw new CodexError("额度重置需要唯一请求标识", 400);
        ValidateResetSelection(creditId, useNextAvailable);
        var baseline = await Read(executable, privateRoot, auth, ct);
        var summary = baseline.Usage["rateLimitResetCredits"];
        if (summary.L("availableCount") < 1) throw new CodexError("当前工作空间没有可用的额度重置", 409);
        if (summary.L("applicableCount") < 1) throw new CodexError("当前额度窗口暂不符合重置条件", 409);
        var identity = CodexAccountStore.Identity(baseline.Auth);
        return await Probe(executable, privateRoot, baseline.Auth, async (client, _, token) => {
            var before = await ReadRateLimits(client, token);
            Verify(identity, before);
            EnsureResetCreditAvailable(before, creditId);
            var parameters = Obj(("idempotencyKey", idempotencyKey));
            if (!string.IsNullOrWhiteSpace(creditId)) parameters["creditId"] = creditId;
            var consumed = await client.Call("account/rateLimitResetCredit/consume", parameters, token);
            var after = await ReadRateLimits(client, token);
            Verify(identity, after);
            return Obj(("outcome", consumed["outcome"]), ("usage", after));
        }, ct);
    }
    public static async Task<(JsonObject Usage, JsonObject Auth)> Read(string executable, string privateRoot, JsonObject auth, CancellationToken ct) {
        // Each request uses this workspace's own token AND routing header, never a live
        // app-server's cached account. A valid access token does not require forced refresh.
        try { return (await Direct(auth, ct), auth.DeepClone().AsObject()); }
        catch (CodexError error) when (error.Status == 401) { }
        var probe = await Probe(executable, privateRoot, auth, (_, refreshed, token) => Direct(refreshed, token), ct);
        return (probe.Result, probe.Auth);
    }
}
