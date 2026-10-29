using RemoteTool.WebApi.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Authorize, Route("api/hermes"), Route("api/hermes/profiles/{profile}"), ServiceFilter(typeof(HermesProfileFilter))]
public sealed class HermesController(HermesBridge bridge, HermesManagement management, HermesAttachments attachments, HermesCompaction compaction, RunRegistry runs, HermesSessionActivity activity, HermesTitleService titles, HermesNativeAttachments nativeAttachments) : ControllerBase
{
    [HttpGet("profiles")] public IActionResult Profiles([FromServices] HermesProfiles profiles) => Ok(profiles.List());
    private static string Id(string value) => Regex.IsMatch(value, "^[a-zA-Z0-9_-]{1,160}$") ? value : throw new ArgumentException("无效的会话或任务标识");
    [HttpGet("sessions/search")] public async Task<IActionResult> SearchSessions([FromQuery] string query, [FromQuery] int offset = 0, CancellationToken ct = default) {
        query = query?.Trim();
        if (string.IsNullOrEmpty(query) || query.Length > 500 || offset < 0) return BadRequest(new { message = "搜索参数无效" });
        Response.Headers.CacheControl = "no-store";
        try { return Ok(await management.Invoke("session_search", JsonSerializer.SerializeToElement(new { query, offset }), ct)); }
        catch (Exception error) when (error is InvalidOperationException or IOException) { return StatusCode(503, new { message = error.Message }); }
    }
    [HttpGet("capabilities")] public async Task<IActionResult> Capabilities(CancellationToken ct) {
        try {
        var result = new System.Text.Json.Nodes.JsonObject { ["attachments"] = true, ["message_actions"] = true };
        try {
            using var upstream = await bridge.SendAsync(HttpMethod.Get, "v1/capabilities", null, null, ct);
            if (upstream.IsSuccessStatusCode) result = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!.AsObject();
            else result["channel"] = false;
        } catch (Exception error) when (error is InvalidOperationException or HttpRequestException or IOException) { result["channel"] = false; }
        result["chuckie_features"] = new System.Text.Json.Nodes.JsonObject { ["attachment_steering"] = true, ["external_session_activity"] = true, ["message_actions"] = true };
        // The loopback API Server is a separate switch in the user's .env and can be off
        // while the channel is reachable. Surface it so clients can prompt instead of
        // silently losing the ability to address runs owned by other clients.
        try {
            var status = await management.Invoke("api_server_status", null, ct);
            result["api_server"] = status.TryGetProperty("data", out var data)
                ? System.Text.Json.Nodes.JsonNode.Parse(data.GetRawText())
                : System.Text.Json.Nodes.JsonNode.Parse(status.GetRawText());
        } catch (Exception error) when (error is InvalidOperationException or IOException or OperationCanceledException) {
            result["api_server"] = new System.Text.Json.Nodes.JsonObject { ["enabled"] = false, ["unknown"] = true };
        }
        return Ok(result);
        } catch (Exception error) when (error is InvalidOperationException or HttpRequestException or IOException) {
            return StatusCode(503, new { message = error is InvalidOperationException ? error.Message : "无法访问本机 Hermes" });
        }
    }
    [HttpPost("api-server")] public async Task<IActionResult> EnableApiServer([FromBody] JsonElement body, [FromServices] HermesGatewayAdmin gateway, CancellationToken ct) {
        Response.Headers.CacheControl = "no-store";
        try {
            var restart = body.ValueKind == JsonValueKind.Object && body.TryGetProperty("restart", out var flag) && flag.ValueKind == JsonValueKind.True;
            var result = await management.Invoke("enable_api_server", JsonSerializer.SerializeToElement(new { restart = false }), ct);
            if (!restart) return Ok(result);
            var response = JsonNode.Parse(result.GetRawText())!.AsObject();
            response["maintenance"] = await gateway.Start(true, ct);
            return Ok(response);
        } catch (Exception error) when (error is InvalidOperationException or IOException or OperationCanceledException) {
            return StatusCode(503, new { message = error.Message });
        }
    }
    [HttpGet("sessions/context-bulk")] public async Task<IActionResult> SessionsContextBulk([FromQuery] string ids, CancellationToken ct) {
        Response.Headers.CacheControl = "no-store";
        var wanted = (ids ?? "").Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Where(id => Regex.IsMatch(id, "^[a-zA-Z0-9_-]{1,80}$")).Distinct(StringComparer.OrdinalIgnoreCase).Take(40).ToArray();
        var result = new System.Text.Json.Nodes.JsonObject();
        if (wanted.Length == 0) return Ok(result);
        var providers = new Dictionary<string, string>();
        SessionReadIsolation.Try("batch", "models", () =>
            providers = activity.SessionModelInfo(wanted).ToDictionary(pair => pair.Key, pair => pair.Value.Provider));
        var models = new Dictionary<string,string>();
        try {
            using var upstream = await bridge.SendAsync(HttpMethod.Get, "api/sessions?limit=50", null, null, ct);
            if (upstream.IsSuccessStatusCode && System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct)) is { } list && list["data"] is System.Text.Json.Nodes.JsonArray rows)
                foreach (var row in rows.OfType<System.Text.Json.Nodes.JsonObject>())
                    if (row["id"]?.ToString() is { } rid && row["model"]?.ToString() is { } mdl) models[rid] = mdl;
        } catch (Exception error) when (error is HttpRequestException or OperationCanceledException or JsonException or InvalidOperationException) { }
        var budget = CancellationTokenSource.CreateLinkedTokenSource(ct); budget.CancelAfter(TimeSpan.FromSeconds(15));
        try {
            var gate = new SemaphoreSlim(5);
            // JsonObject 非线程安全：并发阶段写 ConcurrentDictionary，结束后串行合并。
            var answered = new System.Collections.Concurrent.ConcurrentDictionary<string, System.Text.Json.Nodes.JsonNode>(StringComparer.OrdinalIgnoreCase);
            await Task.WhenAll(wanted.Select(async id => {
                try {
                    await gate.WaitAsync(budget.Token);
                    try {
                        if (ContextCache.TryGetValue(HermesProfileScope.Key(id), out var cached) && DateTime.UtcNow - cached.At < TimeSpan.FromMinutes(10)) {
                            answered[id] = cached.Tokens > 0 ? Obj2(cached.Tokens, cached.Limit) : new System.Text.Json.Nodes.JsonObject();
                            return;
                        }
                        var (tokens, limit) = await ContextUsageAsync(id, providers.GetValueOrDefault(id, ""), models.GetValueOrDefault(id, ""), budget.Token);
                        // 0 值（网关对繁忙/刚创建会话的临时空结果）不写缓存，否则真数据要等 TTL 才能回来。
                        if (tokens > 0) { ContextCache[HermesProfileScope.Key(id)] = (DateTime.UtcNow, tokens, limit); answered[id] = Obj2(tokens, limit); }
                        else answered[id] = new System.Text.Json.Nodes.JsonObject();
                    } finally { gate.Release(); }
                } catch (Exception error) when (!ct.IsCancellationRequested &&
                    (error is OperationCanceledException or HttpRequestException || SessionReadIsolation.Recoverable(error))) { }
            }).ToArray());
            foreach (var pair in answered) result[pair.Key] = pair.Value;
        } catch (OperationCanceledException) { } finally { budget.Dispose(); }
        return Ok(result);
    }
    private static System.Text.Json.Nodes.JsonObject Obj2(long tokens, long limit) =>
        new() { ["context_used"] = tokens, ["context_window"] = limit };
    [HttpGet("sessions/{id}/context")] public async Task<IActionResult> SessionContext(string id, CancellationToken ct) {
        try { return Ok(await ContextAsync(Id(id), ct)); }
        catch (InvalidOperationException error) { return Ok(new { available = false, message = error.Message }); }
        catch (OperationCanceledException) when (!ct.IsCancellationRequested) { return Ok(new { available = false, message = "上下文读取超时，请刷新重试" }); }
    }
    private async Task<JsonObject> ContextAsync(string id, CancellationToken ct) {
        var result = JsonNode.Parse((await management.Invoke("session_context", JsonSerializer.SerializeToElement(new { session_id = id }), ct)).GetRawText())!.AsObject();
        // 与 WindowFrom 同一纠错：自定义网关上 hermes 无法得知真实窗口，已知模型用真实值。
        if (KnownModelWindows.TryGetValue(result["model"]?.ToString() ?? "", out var window) &&
            (result["limit"]?.GetValue<long>() ?? 0) < window) result["limit"] = window;
        try {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(2));
            using var info = await bridge.SendAsync(HttpMethod.Get, "api/sessions/" + id, null, null, timeout.Token);
            if (info.IsSuccessStatusCode) {
                var session = JsonNode.Parse(await info.Content.ReadAsStringAsync(timeout.Token));
                result["title"] = (session?["session"] ?? session)?["title"]?.DeepClone();
            }
        } catch (Exception error) when (!ct.IsCancellationRequested && error is HttpRequestException or OperationCanceledException or JsonException or InvalidOperationException) { }
        return result;
    }
    [HttpGet("sessions/{id}/activity")] public IActionResult SessionActivity(string id) {
        Response.Headers.CacheControl = "no-store";
        return Ok(activity.Read(Id(id)));
    }
    [HttpGet("title-model")] public IActionResult TitleModel() => Ok(titles.Config());
    [HttpPost("title-model")] public Task<IActionResult> SaveTitleModel([FromBody] JsonElement body) => TitleSettings(() => Task.FromResult(titles.Save(JsonNode.Parse(body.GetRawText())!.AsObject())));
    [HttpPost("title-model/test")] public Task<IActionResult> TestTitleModel(CancellationToken ct) => TitleSettings(() => titles.Test(ct));
    [HttpPost("title-model/models")] public Task<IActionResult> TitleModels([FromBody] JsonElement body, CancellationToken ct) => TitleSettings(() => titles.Models(JsonNode.Parse(body.GetRawText())!.AsObject(), ct));
    private async Task<IActionResult> TitleSettings(Func<Task<JsonObject>> action) {
        try { return Ok(await action()); }
        catch (CodexError error) { return StatusCode(error.Status, new { message = error.Message, code = error.Code }); }
    }
    [HttpGet("models")] public Task Models(CancellationToken ct) => Forward(HttpMethod.Get, "v1/models", null, null, ct);
    [HttpPost("sessions/{id}/compact")] public IActionResult Compact(string id) {
        var key = Request.Headers["Idempotency-Key"].ToString();
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) return BadRequest(new { message = "压缩需要唯一请求标识" });
        try { return Ok(compaction.Start(Id(id), key)); }
        catch (InvalidOperationException error) { return Conflict(new { message = error.Message }); }
    }
    [HttpGet("model-options")] public async Task<IActionResult> ModelOptions(CancellationToken ct) {
        using var upstream = await bridge.SendAsync(HttpMethod.Get, "api/model/options", null, null, ct);
        if (!upstream.IsSuccessStatusCode) return StatusCode(502, new { message = "无法读取 Hermes 模型目录" });
        var result = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!.AsObject();
        var info = await management.Invoke("model_reasoning", null, ct);
        if (info.TryGetProperty("reasoning_effort", out var effort)) result["reasoning_effort"] = System.Text.Json.Nodes.JsonValue.Create(effort.ToString());
        return Ok(result);
    }
    [HttpPost("sessions/{id}/model")] public Task SessionModel(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"api/sessions/{Id(id)}/model", body, null, ct);
    [HttpGet("providers")] public Task<IActionResult> Providers(CancellationToken ct) => Settings("providers", null, ct);
    [HttpGet("default-model")] public Task<IActionResult> DefaultModel(CancellationToken ct) => Settings("model_info", null, ct);
    [HttpPost("providers")] public Task<IActionResult> SaveProvider([FromBody] JsonElement body, CancellationToken ct) => Settings("save_provider", body, ct);
    [HttpPost("default-model")] public Task<IActionResult> SetDefault([FromBody] JsonElement body, CancellationToken ct) => Settings("default_model", body, ct);
    private async Task<IActionResult> Settings(string action, JsonElement? body, CancellationToken ct)
    {
        try { return Ok(await management.Invoke(action, body, ct)); }
        catch (InvalidOperationException error) { return StatusCode(503, new { message = error.Message }); }
    }
    /** 会话上下文占用缓存：网关 session_context 单次约 0.5-0.8s，列表不能逐个实时拉。
        运行中的会话 30s 过期，空闲 10min；每次列表请求用 1.2s 总预算补齐最旧的一批。 */
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string,(DateTime At,long Tokens,long Limit)> ContextCache = new(StringComparer.OrdinalIgnoreCase);
    /** 窗口上限是模型属性而非会话属性：按 provider/model 学习一次，之后所有会话复用，不再重复解析。 */
    private static readonly System.Collections.Concurrent.ConcurrentDictionary<string,long> ModelWindows = new(StringComparer.OrdinalIgnoreCase);
    /** 已知模型的真实上下文窗口（字节数单位 token）。网关在自定义提供方上估不准时使用。 */
    private static readonly System.Collections.Generic.Dictionary<string,long> KnownModelWindows = new(StringComparer.OrdinalIgnoreCase) {
        ["k3"] = 1_048_576, ["kimi-k3-1"] = 1_048_576,
    };
    private (long Tokens,long Limit) WindowFrom(JsonNode result, string provider, string model) {
        var tokens = result["tokens"]?.GetValue<long>() ?? 0;
        var limit = result["limit"]?.GetValue<long>() ?? 0;
        var name = result["model"]?.ToString();
        if (!string.IsNullOrEmpty(name)) model = name;
        var key = HermesProfileScope.Key((provider ?? "") + "/" + (model ?? ""));
        // 自定义网关（custom:*）上 hermes 无法得知真实上下文窗口，会按保守默认值（如 256K）上报；
        // 已知模型的真实窗口以本表为准，仅上调不下调。
        var knownWindow = KnownModelWindows.TryGetValue(model ?? "", out var knownMax) ? knownMax : 0;
        if (limit > 0 && limit >= knownWindow) ModelWindows[key] = limit;
        else if (knownWindow > 0) limit = knownWindow;
        else if (ModelWindows.TryGetValue(key, out var known)) limit = known;
        // 网关没给出窗口上限时按 1M 兜底（主流长上下文模型的实际量级）。
        if (limit <= 0) limit = 1_000_000;
        return (tokens, limit);
    }
    private async Task<(long Tokens,long Limit)> ContextUsageAsync(string id, string provider, string model, CancellationToken ct) {
        var raw = await management.Invoke("session_context", JsonSerializer.SerializeToElement(new { session_id = id }), ct);
        return WindowFrom(JsonNode.Parse(raw.GetRawText())!, provider, model);
    }
    [HttpGet("sessions")] public async Task<IActionResult> Sessions([FromQuery] int offset = 0, CancellationToken ct = default) {
        Response.Headers.CacheControl = "no-store";
        try {
            using var upstream = await bridge.SendAsync(HttpMethod.Get, $"api/sessions?limit=50&offset={Math.Max(0, offset)}", null, null, ct);
            if (!upstream.IsSuccessStatusCode) return StatusCode(502, new { message = "无法读取 Hermes 会话列表" });
            var result = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!;
            if (result["data"] is System.Text.Json.Nodes.JsonArray rows) {
                var ids = rows.Select(row => row?["id"]?.ToString()).ToArray();
                IReadOnlyDictionary<string, JsonObject> activities = new Dictionary<string, JsonObject>();
                IReadOnlyDictionary<string, string> previews = new Dictionary<string, string>();
                SessionReadIsolation.Try("batch", "activities", () => activities = activity.ReadMany(ids));
                SessionReadIsolation.Try("batch", "previews", () => previews = LatestSessionPreview.Read(activity.DatabasePath, ids, false));
                foreach (var row in rows.OfType<System.Text.Json.Nodes.JsonObject>()) {
                    if (row["id"]?.ToString() is not string id) continue;
                    SessionReadIsolation.Try(id, "list", () => {
                    var running = activities.TryGetValue(id, out var state) && state["available"]?.GetValue<bool>() == true && state["running"]?.GetValue<bool>() == true;
                    if (previews.TryGetValue(id, out var text)) { row["latest_user_message"] = text; row["preview"] = text; }
                    if (activities.TryGetValue(id, out state) && state["available"]?.GetValue<bool>() == true)
                        row["status"] = running ? "running" : "idle";
                    if (ContextCache.TryGetValue(HermesProfileScope.Key(id), out var cached) && DateTime.UtcNow - cached.At < TimeSpan.FromMinutes(running ? 0.5 : 10)) {
                        if (cached.Tokens > 0 && cached.Limit > 0) { row["context_used"] = cached.Tokens; row["context_window"] = cached.Limit; }
                    }
                    });
                }
            }
            return Ok(result);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception error) when (error is InvalidOperationException or IOException or HttpRequestException or UnauthorizedAccessException) {
            return StatusCode(503, new { message = error is InvalidOperationException ? error.Message : "无法访问本机 Hermes，请检查 API Server 和服务端密钥文件权限" });
        }
    }
    [HttpPost("sessions")] public async Task CreateSession([FromBody] JsonElement body, [FromServices] Services.PhoneControl.PhoneSessionIntegration phone, CancellationToken ct) {
        var nativeBody = JsonNode.Parse(body.GetRawText())!.AsObject(); nativeBody.Remove("auto_title");
        JsonObject? phoneBinding;
        try { phoneBinding = await phone.Prepare(nativeBody, "hermes", Request, ct); }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException) {
            Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = error.Message, code = "phone_tools_unavailable", delivery = "rejected" }, ct); return;
        }
        using var response = await bridge.SendAsync(HttpMethod.Post, "api/sessions", JsonSerializer.SerializeToElement(nativeBody), null, ct);
        var payload = await response.Content.ReadAsStringAsync(ct);
        if (response.IsSuccessStatusCode) {
            var value = JsonNode.Parse(payload); var session = value?["session"] ?? value;
            if (session?["id"] != null) {
                phone.Complete(phoneBinding, session["id"]!.ToString(), "hermes");
                titles.Register(session["id"]!.ToString(), session["title"]?.ToString() ?? "", body.TryGetProperty("auto_title", out var automatic) && automatic.ValueKind == JsonValueKind.True);
            }
        }
        else phone.Abort(phoneBinding);
        Response.StatusCode = (int)response.StatusCode; Response.ContentType = "application/json"; await Response.WriteAsync(payload, ct);
    }
    [HttpGet("sessions/{id}")] public async Task SessionInfo(string id, CancellationToken ct)
    {
        using var upstream = await bridge.SendAsync(HttpMethod.Get, $"api/sessions/{Id(id)}", null, null, ct);
        var payload = await upstream.Content.ReadAsStringAsync(ct);
        // 网关的会话详情不包含 provider（用户自定义提供方上的会话会缺省成 "custom"）；
        // 从 state.db 的 model_config / billing_provider 注入权威值，供客户端正确回显与高亮。
        if (upstream.IsSuccessStatusCode && System.Text.Json.Nodes.JsonNode.Parse(payload) is System.Text.Json.Nodes.JsonObject node)
        {
            var session = (node["session"] as System.Text.Json.Nodes.JsonObject) ?? node;
            var info = await SessionModelSettingAsync(Id(id), ct);
            if (!string.IsNullOrEmpty(info?.Provider))
            {
                session["provider"] = info.Provider;
                session["modelProvider"] = info.Provider;
            }
            if (!string.IsNullOrEmpty(info?.Effort)) session["reasoning_effort"] = info.Effort;
            payload = node.ToJsonString();
        }
        Response.StatusCode = (int)upstream.StatusCode;
        Response.ContentType = upstream.Content.Headers.ContentType?.ToString() ?? "application/json";
        await Response.WriteAsync(payload, ct);
    }
    private async Task<HermesSessionActivity.SessionModelSetting?> SessionModelSettingAsync(string id, CancellationToken ct)
    {
        var info = activity.SessionModelInfo(new[] { id }).GetValueOrDefault(id);
        if (info == null || (!string.IsNullOrEmpty(info.Provider) && info.Provider != "custom") || string.IsNullOrWhiteSpace(info.BaseUrl)) return info;
        try {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(2));
            using var response = await bridge.SendAsync(HttpMethod.Get, "api/model/options", null, null, timeout.Token);
            if (response.IsSuccessStatusCode && JsonNode.Parse(await response.Content.ReadAsStringAsync(timeout.Token)) is JsonObject catalog)
                return info with { Provider = HermesProviderIdentity.Resolve(info.Provider, info.BaseUrl, catalog) };
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception error) when (error is OperationCanceledException or InvalidOperationException or HttpRequestException or IOException or JsonException) { }
        return info;
    }
    [HttpPost("sessions/{id}/fork")] public Task<IActionResult> ForkSession(string id, [FromBody] JsonElement body, CancellationToken ct) => MessageAction(id, "fork", body, ct);
    [HttpPost("sessions/{id}/rewind")] public Task<IActionResult> RewindSession(string id, [FromBody] JsonElement body, CancellationToken ct) => MessageAction(id, "rewind", body, ct);
    private async Task<IActionResult> MessageAction(string id, string operation, JsonElement body, CancellationToken ct) {
        var request = JsonNode.Parse(body.GetRawText())!.AsObject();
        request["session_id"] = Id(id); request["operation"] = operation;
        if (operation == "rewind" && activity.Read(id)["running"]?.GetValue<bool>() == true)
            return Conflict(new { message = "请先等待任务结束再编辑", code = "session_running", delivery = "rejected" });
        try {
            var result = JsonNode.Parse((await management.Invoke("message_action", JsonSerializer.SerializeToElement(request), ct)).GetRawText())!.AsObject();
            if (result["error"]?.ToString() is string failure) return Conflict(new { message = failure, code = "message_action_rejected", delivery = "rejected" });
            result.Remove("message_id_map");
            return Ok(result);
        } catch (InvalidOperationException error) { return StatusCode(502, new { message = error.Message, code = "message_action_unknown", delivery = "unknown" }); }
    }
    [HttpPost("sessions/{id}/pin")] public Task PinSession(string id, [FromBody] JsonElement body, CancellationToken ct) {
        if (!body.TryGetProperty("pinned", out var pinned) || pinned.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) {
            Response.StatusCode = 400;
            return Response.WriteAsJsonAsync(new { message = "pinned 必须为布尔值" }, ct);
        }
        return Forward(HttpMethod.Patch, $"api/sessions/{Id(id)}", JsonSerializer.SerializeToElement(new { pinned = pinned.GetBoolean() }), null, ct);
    }
    [HttpPatch("sessions/{id}")] public Task RenameSession(string id, [FromBody] JsonElement body, CancellationToken ct)
    {
        var title = body.GetProperty("title").GetString()?.Trim();
        if (string.IsNullOrEmpty(title) || title.Length > 160) {
            Response.StatusCode = 400;
            return Response.WriteAsJsonAsync(new { message = "会话名称应为 1–160 个字符" }, ct);
        }
        return RenameTitle(Id(id), title, ct);
    }
    private async Task RenameTitle(string id, string title, CancellationToken ct) {
        using var response = await titles.Rename(id, JsonSerializer.SerializeToElement(new { title }), ct);
        Response.StatusCode = (int)response.StatusCode; Response.ContentType = "application/json";
        await Response.WriteAsync(await response.Content.ReadAsStringAsync(ct), ct);
    }
    [HttpDelete("sessions/{id}"), HttpPost("sessions/{id}/delete")] public async Task DeleteSession(string id, [FromServices] Services.PhoneControl.PhoneGateway phone, CancellationToken ct) {
        await Forward(HttpMethod.Delete, $"api/sessions/{Id(id)}", null, null, ct);
        if (Response.StatusCode < 300) phone.RevokeSession("hermes", HermesProfileScope.Name, id);
    }
    [HttpGet("sessions/{id}/messages")] public async Task<IActionResult> Messages(string id, [FromQuery] int limit = 100, [FromQuery] string from_id = null, [FromQuery] string older_before = null, CancellationToken ct = default)
    {
        try { return Ok(await HistoryAsync(Id(id), limit, from_id, older_before, ct)); }
        catch (InvalidOperationException error) { return StatusCode(502, new { message = error.Message }); }
        catch (Exception error) when (error is HttpRequestException or IOException) { return StatusCode(502, new { message = "无法读取 Hermes 会话历史" }); }
    }
    private async Task<JsonNode> HistoryAsync(string id, int limit, string from_id, string older_before, CancellationToken ct)
    {
        var json = await HermesHistoryPages.ReadAsync(async (pageSize, offset, token) => {
            using var upstream = await bridge.SendAsync(HttpMethod.Get,
                $"api/sessions/{id}/messages?inline_images=false&order=latest&limit={pageSize}&offset={offset}", null, null, token);
            if (!upstream.IsSuccessStatusCode) throw new InvalidOperationException("无法读取 Hermes 会话历史");
            return System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(token));
        }, limit, from_id, older_before, ct);
        var state = activity.Read(id);
        json = CompletedToolHistory.Reduce(json, state["running"]?.GetValue<bool>() == true,
            state["started_at"]?.GetValue<long>() ?? 0, id, state["revision"]?.ToString() ?? "");
        json = AgentHistoryWindow.Select(json, limit, from_id, older_before);
        json = AgentInteractiveMessages.Present(json);
        json = activity.PresentMessageEditability(json);
        return nativeAttachments.Present(id, json?.ToJsonString() ?? "{}");
    }
    [HttpPost("sessions/{id}/messages/{messageId}/attachments")] public IActionResult BindAttachments(string id, string messageId, [FromBody] JsonElement body)
    { Id(id); if(!long.TryParse(messageId,out var mid) || mid<=0) return BadRequest(new {message="无效消息标识"}); return Ok(new { success = true }); }
    [HttpGet("runs/lookup")] public async Task<IActionResult> LookupRun(string key, string session_id, CancellationToken ct) {
        if (!Regex.IsMatch(key ?? "", "^[a-zA-Z0-9_-]{16,120}$")) return BadRequest(new { message = "无效的请求标识" });
        if (!Regex.IsMatch(session_id ?? "", "^[a-zA-Z0-9_-]{1,160}$")) return BadRequest(new { message = "无效的会话标识" });
        var session = Id(session_id ?? "");
        var stored = await management.Invoke("run_lookup", JsonSerializer.SerializeToElement(new { key, session_id = session }), ct);
        if (!stored.TryGetProperty("found", out var found) || !found.GetBoolean()) return Ok(new { found = false });
        // Hermes itself authorizes the run using the configured Bearer principal.
        // Reading a reservation is not sufficient to expose or confirm another scope's run.
        using var upstream = await bridge.SendAsync(HttpMethod.Get, "v1/runs/" + Id(stored.GetProperty("run_id").GetString()!), null, null, ct);
        if (upstream.StatusCode == System.Net.HttpStatusCode.NotFound || upstream.StatusCode == System.Net.HttpStatusCode.Unauthorized || upstream.StatusCode == System.Net.HttpStatusCode.Forbidden)
            return Ok(new { found = false });
        if (!upstream.IsSuccessStatusCode) return StatusCode(502, new { message = "无法核对 Hermes 任务状态" });
        var result = JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!.AsObject();
        if (result["session_id"]?.ToString() != session) return Ok(new { found = false });
        result["found"] = true;
        return Ok(result);
    }
    [HttpPost("runs")] public async Task Run([FromBody] JsonElement body, CancellationToken ct)
    {
        var key = Request.Headers["Idempotency-Key"].ToString();
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) { Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = "任务提交需要唯一的 Idempotency-Key" }, ct); return; }
        try
        {
            // 会话锁定的 provider/model 必须随每次提交下发：App 在后台/队列提交时不带
            // provider（只有前台选中会话才带），body 缺 provider 时网关 /v1/runs 会落到
            // config 默认通道（与该会话锁定的提供方不一致）。缺省时用 state.db 的
            // browser_model_lock 补齐，与 chat 通道的会话锁语义一致。
            try
            {
                var runSessionId = body.TryGetProperty("session_id", out var rs) ? rs.GetString() : null;
                if (!string.IsNullOrEmpty(runSessionId)) {
                    var info = body.TryGetProperty("provider", out var selectedProvider) && selectedProvider.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(selectedProvider.GetString())
                        ? activity.SessionModelInfo(new[] { Id(runSessionId) }).GetValueOrDefault(Id(runSessionId))
                        : await SessionModelSettingAsync(Id(runSessionId), ct);
                    if (info != null) {
                        var patched = System.Text.Json.Nodes.JsonNode.Parse(body.GetRawText())!.AsObject();
                        // 壳值 "custom" 只是计费占位（billing_provider 对自定义提供方恒为 custom），
                        // 注入它反而让网关解析到错误的提供方；只在拿到完整 slug 时补齐。
                        if (string.IsNullOrEmpty(patched["provider"]?.ToString()) &&
                            !string.IsNullOrEmpty(info.Provider) && info.Provider != "custom") patched["provider"] = info.Provider;
                        if (string.IsNullOrEmpty(patched["model"]?.ToString()) && !string.IsNullOrEmpty(info.Model)) patched["model"] = info.Model;
                        body = JsonSerializer.SerializeToElement(patched);
                    }
                }
            }
            catch (System.Text.Json.JsonException) { }
            using var upstream = await bridge.SendAsync(HttpMethod.Post, "v1/runs", nativeAttachments.PrepareRun(body, key), key, ct, Request.Headers["Last-Event-ID"].ToString());
            Response.Headers.CacheControl = "no-store";
            var payload = await upstream.Content.ReadAsStringAsync(ct);
            if (!upstream.IsSuccessStatusCode)
            {
                Response.StatusCode = upstream.StatusCode == System.Net.HttpStatusCode.Unauthorized ? 502 : (int)upstream.StatusCode;
                await WriteUpstreamError(payload, upstream.StatusCode, ct);
                return;
            }
            // 登记「会话当前活跃 run」供多端共享（平板打开同一会话可挂载实时进度）。
            try
            {
                using var doc = JsonDocument.Parse(payload);
                var runId = doc.RootElement.TryGetProperty("run_id", out var r) ? r.GetString() : null;
                var sessionId = body.TryGetProperty("session_id", out var s) ? s.GetString() : null;
                if (!string.IsNullOrEmpty(runId) && !string.IsNullOrEmpty(sessionId)) {
                    runs.Register(HermesProfileScope.AgentKey, sessionId, runId);
                    titles.Accepted(sessionId, body.TryGetProperty("input", out var input) ? input.ToString() : "");
                }
            }
            catch (JsonException) { }
            Response.StatusCode = (int)upstream.StatusCode;
            Response.ContentType = upstream.Content.Headers.ContentType?.ToString() ?? "application/json";
            await Response.WriteAsync(payload, ct);
        }
        catch (Exception error) when (error is ArgumentException or FileNotFoundException) { Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = error.Message }, ct); }
    }

    /// <summary>多端共享：该会话当前是否有活跃 run（供其它设备挂载实时进度）。终态自动清除。</summary>
    [HttpGet("sessions/{id}/active-run")] public async Task ActiveRun(string id, CancellationToken ct)
    {
        Response.Headers.CacheControl = "no-store";
        try { await Response.WriteAsJsonAsync(await ActiveRunAsync(Id(id), ct) ?? (object)new { run_id = (string?)null }, ct); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
        catch (Exception error) when (error is InvalidOperationException or HttpRequestException or IOException) {
            Response.StatusCode = 502;
            await Response.WriteAsJsonAsync(new { message = error.Message }, ct);
        }
    }
    /// <summary>返回当前活跃 run 的完整文档；无活跃 run 时返回 null。供 /active-run 与 /overview 共用。</summary>
    private async Task<JsonObject?> ActiveRunAsync(string sessionId, CancellationToken ct)
    {
        var registered = runs.Query(HermesProfileScope.AgentKey, sessionId);
        var candidates = new[] { registered }.Concat(activity.ActiveRunCandidates(sessionId))
            .Where(run => !string.IsNullOrEmpty(run)).Distinct();
        foreach (var runId in candidates) {
            if (runId.StartsWith("hcompact_")) {
                try {
                    var status = compaction.Status(runId);
                    if (status["status"]?.ToString() is "started" or "submitting") return status;
                } catch (KeyNotFoundException) { }
                runs.Clear(HermesProfileScope.AgentKey, sessionId, runId); continue;
            }
            // The local database only discovers IDs. Hermes verifies the credential's
            // ownership and the authoritative run state before it is exposed to the phone.
            using var upstream = await bridge.SendAsync(HttpMethod.Get, $"v1/runs/{runId}", null, null, ct, null);
            if (upstream.StatusCode == System.Net.HttpStatusCode.NotFound)
            {
                runs.Clear(HermesProfileScope.AgentKey, sessionId, runId);
                continue;
            }
            if (!upstream.IsSuccessStatusCode) throw new InvalidOperationException("无法核对 Hermes 当前任务，请稍后重试");
            var run = JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!.AsObject();
            var state = run["status"]?.ToString() ?? "";
            var owner = run["session_id"]?.ToString();
            if (owner != sessionId || state is "completed" or "failed" or "cancelled" or "interrupted" or "acceptance_unknown") {
                runs.Clear(HermesProfileScope.AgentKey, sessionId, runId); continue;
            }
            if (state is not ("started" or "submitting" or "queued" or "running" or "stopping" or "waiting_for_approval")) continue;
            if (registered != runId) runs.Register(HermesProfileScope.AgentKey, sessionId, runId);
            return run;
        }
        return null;
    }
    [HttpGet("runs/{id}")] public async Task Status(string id, CancellationToken ct) {
        if (!Id(id).StartsWith("hcompact_")) { await Forward(HttpMethod.Get, $"v1/runs/{id}", null, null, ct); return; }
        try { await Response.WriteAsJsonAsync(compaction.Status(id), ct); }
        catch (KeyNotFoundException) { Response.StatusCode = 404; await Response.WriteAsJsonAsync(new { message = "压缩任务不存在" }, ct); }
    }
    /// <summary>一次往返取齐打开会话所需的数据：详情、历史、活跃 run、外部活动、上下文。
    /// 单项失败不拖垮整体——失败项为 null，前端按需回退到对应的单独请求。</summary>
    [HttpGet("sessions/{id}/overview")] public async Task<IActionResult> Overview(string id, [FromQuery] int limit = 80, CancellationToken ct = default)
    {
        Response.Headers.CacheControl = "no-store";
        var sessionId = Id(id);
        async Task<JsonNode?> Part(Func<Task<JsonNode?>> work) {
            try { return await work(); }
            catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
            catch (Exception) { return null; }
        }
        var detailTask = Part(async () => {
            using var upstream = await bridge.SendAsync(HttpMethod.Get, $"api/sessions/{sessionId}", null, null, ct);
            if (!upstream.IsSuccessStatusCode) return null;
            if (System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct)) is not JsonObject detail) return null;
            var session = (detail["session"] as JsonObject) ?? detail;
            var info = await SessionModelSettingAsync(sessionId, ct);
            if (!string.IsNullOrEmpty(info?.Provider)) { session["provider"] = info.Provider; session["modelProvider"] = info.Provider; }
            if (!string.IsNullOrEmpty(info?.Effort)) session["reasoning_effort"] = info.Effort;
            return detail;
        });
        var historyTask = Part(() => HistoryAsync(sessionId, Math.Clamp(limit, 1, 100), null, null, ct));
        var runTask = Part(async () => (JsonNode?)(await ActiveRunAsync(sessionId, ct) ?? new JsonObject { ["run_id"] = null }));
        var contextTask = Part(async () => {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
            timeout.CancelAfter(TimeSpan.FromSeconds(6));
            return await ContextAsync(sessionId, timeout.Token);
        });
        await Task.WhenAll(detailTask, historyTask, runTask, contextTask);
        var run = runTask.Result;
        JsonNode? files = null;
        try { files = JsonSerializer.SerializeToNode(new { data = nativeAttachments.List(sessionId) }); }
        catch (Exception) { /* The app can use the existing files endpoint. */ }
        return Ok(new JsonObject {
            ["detail"] = detailTask.Result,
            ["history"] = historyTask.Result,
            ["active_run"] = run?.DeepClone(),
            ["run"] = run?["run_id"] != null ? run.DeepClone() : null,
            ["activity"] = activity.Read(sessionId),
            ["context"] = contextTask.Result,
            ["files"] = files,
        });
    }
    [HttpGet("sessions/{id}/files")] public IActionResult Files(string id) => Ok(new { data = nativeAttachments.List(Id(id)) });
    [HttpGet("sessions/{id}/tools/{summaryId}")] public async Task<IActionResult> ToolDetails(string id, string summaryId, [FromQuery] int offset = 0, CancellationToken ct = default) {
        var session = Id(id); var key = Id(summaryId); var state = activity.Read(session);
        var revision = state["revision"]?.ToString() ?? "";
        var cached = ToolHistoryCache.Read("hermes", session, key, offset, true, revision);
        if (cached != null) return Ok(cached);
        using var upstream = await bridge.SendAsync(HttpMethod.Get, $"api/sessions/{session}/messages?inline_images=false", null, null, ct);
        if (!upstream.IsSuccessStatusCode) return StatusCode(502, new { message = "无法读取工具记录" });
        CompletedToolHistory.Reduce(JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct)), state["running"]?.GetValue<bool>() == true,
            state["started_at"]?.GetValue<long>() ?? 0, session, revision);
        var result = ToolHistoryCache.Read("hermes", session, key, offset, false, revision);
        return result != null ? Ok(result) : NotFound(new { message = "该轮记录已变化或不再存在，请刷新会话" });
    }
    [HttpPost("sessions/{id}/files")]
    [RequestSizeLimit(501L * 1024 * 1024)]
    [RequestFormLimits(MultipartBodyLengthLimit = 501L * 1024 * 1024)]
    public async Task<IActionResult> Upload(string id, IFormFile file, CancellationToken ct)
    {
        try { return Ok(await attachments.Upload(Id(id), file, ct)); }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
    }
    [HttpGet("sessions/{id}/files/{fileId}")] public IActionResult DownloadFile(string id, string fileId)
    {
        try {
            var source=nativeAttachments.Resolve(Id(id),Id(fileId));
            return source.Bytes!=null?File(source.Bytes,source.Mime):PhysicalFile(source.Path,source.Mime,enableRangeProcessing:true);
        }
        catch (FileNotFoundException) { return NotFound(new { message = "附件不存在" }); }
    }
    [HttpGet("runs/{id}/events")] public Task Events(string id, CancellationToken ct) => Id(id).StartsWith("hcompact_") ? compaction.Events(id, Response, ct) : Forward(HttpMethod.Get, $"v1/runs/{id}/events", null, null, ct);
    [HttpPost("runs/{id}/stop")] public Task Stop(string id, CancellationToken ct) {
        if (!Id(id).StartsWith("hcompact_")) return Forward(HttpMethod.Post, $"v1/runs/{id}/stop", null, null, ct);
        try { compaction.Stop(id); return Response.WriteAsJsonAsync(new { stopped = true }, ct); }
        catch (KeyNotFoundException) { Response.StatusCode = 404; return Response.WriteAsJsonAsync(new { message = "压缩任务不存在" }, ct); }
    }
    [HttpPost("runs/{id}/steer")] public async Task Steer(string id, [FromBody] JsonElement body, CancellationToken ct) {
        var runId = Id(id);
        var key = Request.Headers["Idempotency-Key"].ToString();
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) { Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = "插话需要唯一请求标识", delivery = "rejected" }, ct); return; }
        if (body.TryGetProperty("attachment_ids", out var ids) && ids.GetArrayLength() > 0) {
            try {
                using var status = await bridge.SendAsync(HttpMethod.Get, $"v1/runs/{runId}", null, null, ct);
                if (!status.IsSuccessStatusCode) { Response.StatusCode = (int)status.StatusCode; await Response.WriteAsJsonAsync(new { message = "无法核对当前 Hermes 任务" }, ct); return; }
                using var doc = JsonDocument.Parse(await status.Content.ReadAsStringAsync(ct));
                if (!doc.RootElement.TryGetProperty("session_id", out var field) || string.IsNullOrEmpty(field.GetString())) {
                    Response.StatusCode = 409; await Response.WriteAsJsonAsync(new { message = "当前任务未返回会话标识，无法核对附件归属" }, ct); return;
                }
                var session = field.GetString()!;
                body = nativeAttachments.PrepareSteer(body, session, key);
            } catch (Exception error) when (error is ArgumentException or FileNotFoundException) {
                Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = error.Message }, ct); return;
            }
        }
        await Forward(HttpMethod.Post, $"v1/runs/{runId}/steer", body, key, ct);
    }
    [HttpPost("runs/{id}/approval")] public Task Approval(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"v1/runs/{Id(id)}/approval", body, null, ct);
    [HttpPost("responses")] public Task Responses([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "v1/responses", body, null, ct);

    private async Task WriteUpstreamError(string payload, System.Net.HttpStatusCode status, CancellationToken ct) {
        string? message = null, code = null, delivery = null;
        try {
            using var doc = JsonDocument.Parse(payload);
            var root = doc.RootElement;
            if (root.ValueKind == JsonValueKind.Object) {
                if (root.TryGetProperty("message", out var text) && text.ValueKind == JsonValueKind.String) message = text.GetString();
                if (root.TryGetProperty("code", out var kind) && kind.ValueKind == JsonValueKind.String) code = kind.GetString();
                if (root.TryGetProperty("delivery", out var state) && state.ValueKind == JsonValueKind.String) delivery = state.GetString();
                if (root.TryGetProperty("error", out var nested) && nested.ValueKind == JsonValueKind.Object) {
                    if (string.IsNullOrWhiteSpace(message) && nested.TryGetProperty("message", out var detail) && detail.ValueKind == JsonValueKind.String) message = detail.GetString();
                    if (string.IsNullOrWhiteSpace(code) && nested.TryGetProperty("code", out var nestedCode) && nestedCode.ValueKind == JsonValueKind.String) code = nestedCode.GetString();
                    if (delivery == null && nested.TryGetProperty("delivery", out var nestedDelivery) && nestedDelivery.ValueKind == JsonValueKind.String) delivery = nestedDelivery.GetString();
                }
            }
        } catch (JsonException) { }
        message = !string.IsNullOrWhiteSpace(message) && message.Length <= 500 ? message : $"Hermes 请求失败（HTTP {(int)status}）";
        code = code is { Length: <= 80 } && Regex.IsMatch(code, "^[a-zA-Z0-9_-]+$") ? code : null;
        delivery = delivery is "accepted" or "rejected" or "unknown" ? delivery :
            status == System.Net.HttpStatusCode.Conflict && code is ("permission_denied" or "admission_conflict" or "invalid_params") ? "rejected" :
            status is System.Net.HttpStatusCode.BadRequest or System.Net.HttpStatusCode.UnprocessableEntity ? "rejected" : "unknown";
        if (status == System.Net.HttpStatusCode.Conflict && code == "permission_denied")
            message = "Hermes 拒绝当前接口接续此会话。旧 CLI 会话可从最后一条回复分叉后继续，原会话会保留。";
        await Response.WriteAsJsonAsync(new { message, code, delivery }, ct);
    }

    private async Task Forward(HttpMethod method, string path, JsonElement? body, string? key, CancellationToken ct)
    {
        try
        {
            using var upstream = await bridge.SendAsync(method, path, body, key, ct, Request.Headers["Last-Event-ID"].ToString());
            Response.Headers.CacheControl = "no-store";
            if (!upstream.IsSuccessStatusCode)
            {
                Response.StatusCode = upstream.StatusCode == System.Net.HttpStatusCode.Unauthorized ? 502 : (int)upstream.StatusCode;
                await WriteUpstreamError(await upstream.Content.ReadAsStringAsync(ct), upstream.StatusCode, ct);
                return;
            }
            Response.StatusCode = (int)upstream.StatusCode;
            Response.ContentType = upstream.Content.Headers.ContentType?.ToString() ?? "application/json";
            if (Response.ContentType.StartsWith("text/event-stream"))
            {
                HttpContext.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpResponseBodyFeature>()?.DisableBuffering();
                Response.Headers["X-Accel-Buffering"] = "no";
                using var reader = new StreamReader(await upstream.Content.ReadAsStreamAsync(ct));
                string? line;
                while ((line = await reader.ReadLineAsync(ct)) != null) { await Response.WriteAsync(line + "\n", ct); await Response.Body.FlushAsync(ct); }
            }
            else await upstream.Content.CopyToAsync(Response.Body, ct);
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
        catch (Exception error) when (!Response.HasStarted && error is InvalidOperationException or IOException or HttpRequestException or UnauthorizedAccessException)
        {
            Response.StatusCode = 503;
            await Response.WriteAsJsonAsync(new { message = error is InvalidOperationException ? error.Message : "无法访问本机 Hermes，请检查 API Server 和服务端密钥文件权限" }, ct);
        }
    }
}
