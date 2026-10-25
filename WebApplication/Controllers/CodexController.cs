using RemoteTool.WebApi.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Authorize, Route("api/codex")]
public sealed class CodexController(CodexBridge bridge, [FromKeyedServices("codex")] HermesAttachments attachments, RunRegistry runs, CodexSessionActivity activity) : ControllerBase
{
    private static string Id(string value) => Regex.IsMatch(value, "^[a-zA-Z0-9_-]{1,160}$") ? value : throw new ArgumentException("无效会话或任务标识");
    [HttpGet("capabilities")] public Task Capabilities(CancellationToken ct) => Forward(HttpMethod.Get, "capabilities", null, ct);
    [HttpGet("sessions/search")] public Task SearchSessions([FromQuery] string query, [FromQuery] string cursor, CancellationToken ct) =>
        Forward(HttpMethod.Get, $"sessions/search?query={Uri.EscapeDataString(query ?? "")}" +
            (string.IsNullOrEmpty(cursor) ? "" : $"&cursor={Uri.EscapeDataString(cursor)}"), null, ct);
    [HttpGet("projects")] public Task Projects(CancellationToken ct) => Forward(HttpMethod.Get, "projects", null, ct);
    [HttpGet("model-options")] public Task Models(CancellationToken ct) => Forward(HttpMethod.Get, "model-options", null, ct);
    [HttpGet("providers")] public Task Providers(CancellationToken ct) => Forward(HttpMethod.Get, "providers", null, ct);
    [HttpGet("title-model")] public Task TitleModel(CancellationToken ct) => Forward(HttpMethod.Get, "title-model", null, ct);
    [HttpPost("title-model")] public Task SaveTitleModel([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "title-model", body, ct);
    [HttpPost("title-model/test")] public Task TestTitleModel(CancellationToken ct) => Forward(HttpMethod.Post, "title-model/test", JsonSerializer.SerializeToElement(new { }), ct);
    [HttpPost("title-model/models")] public Task TitleModels([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "title-model/models", body, ct);
    [HttpPost("providers")] public Task SaveProvider([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "providers", body, ct);
    [HttpGet("default-model")] public Task Default(CancellationToken ct) => Forward(HttpMethod.Get, "default-model", null, ct);
    [HttpPost("default-model")] public Task SetDefault([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "default-model", body, ct);
    [HttpGet("sessions")] public Task Sessions([FromQuery] string cursor, CancellationToken ct) => Forward(HttpMethod.Get, "sessions" + (cursor == null ? "" : "?cursor=" + Uri.EscapeDataString(cursor)), null, ct);
    [HttpPost("sessions")] public async Task<IActionResult> Create([FromBody] JsonElement body, [FromServices] Services.PhoneControl.PhoneSessionIntegration phone, CancellationToken ct) {
        var native = JsonNode.Parse(body.GetRawText())!.AsObject(); JsonObject? binding = null;
        try {
            binding = await phone.Prepare(native, "codex", Request, ct);
            using var upstream = await bridge.SendAsync(HttpMethod.Post, "sessions", JsonSerializer.SerializeToElement(native), ct);
            var payload = await upstream.Content.ReadAsStringAsync(ct);
            if (upstream.IsSuccessStatusCode && JsonNode.Parse(payload)?["session"]?["id"]?.ToString() is string id) phone.Complete(binding, id, "codex");
            else if (!upstream.IsSuccessStatusCode) phone.Abort(binding);
            return new ContentResult { StatusCode = (int)upstream.StatusCode, ContentType = "application/json", Content = payload };
        } catch (Exception error) when (error is ArgumentException or InvalidOperationException) {
            phone.Abort(binding); return BadRequest(new { message = error.Message, code = "phone_tools_unavailable", delivery = "rejected" });
        }
    }
    [HttpGet("sessions/{id}")] public Task SessionInfo(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}", null, ct);
    [HttpGet("sessions/{id}/context")] public Task Context(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/context", null, ct);
    [HttpPost("sessions/{id}/takeover")] public Task Takeover(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/takeover", body, ct);
    [HttpPost("sessions/{id}/compact")] public Task Compact(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/compact", JsonSerializer.SerializeToElement(new { }), ct, Request.Headers["Idempotency-Key"].ToString());
    [HttpPatch("sessions/{id}")] public Task Rename(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Patch, $"sessions/{Id(id)}", body, ct);
    [HttpPost("sessions/{id}/delete")] public async Task Delete(string id, [FromServices] Services.PhoneControl.PhoneGateway phone, CancellationToken ct) {
        await Forward(HttpMethod.Post, $"sessions/{Id(id)}/delete", JsonSerializer.SerializeToElement(new { }), ct);
        if (Response.StatusCode < 300) phone.RevokeSession("codex", "default", id);
    }
    [HttpPost("sessions/{id}/pin")] public Task Pin(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/pin", body, ct);
    [HttpPost("sessions/{id}/archive")] public Task Archive(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/archive", body, ct);
    [HttpPost("sessions/{id}/move")] public Task Move(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/move", body, ct);
    [HttpPost("sessions/{id}/fork")] public async Task Fork(string id, [FromBody] JsonElement body, CancellationToken ct) {
        using var response = await bridge.SendAsync(HttpMethod.Post, $"sessions/{Id(id)}/fork", body, ct);
        var payload = await response.Content.ReadAsStringAsync(ct);
        if (response.IsSuccessStatusCode) {
            var value = System.Text.Json.Nodes.JsonNode.Parse(payload)!.AsObject();
            if (value["message_id_map"] is System.Text.Json.Nodes.JsonObject mapping && value["session"]?["id"]?.ToString() is string forkId) {
                try { attachments.Inherit(Id(id), Id(forkId), mapping); }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException) { value["warning"] = "分叉已创建，附件预览索引暂未复制；原附件仍保留。"; }
            }
            value.Remove("message_id_map"); payload = value.ToJsonString();
        }
        Response.StatusCode = (int)response.StatusCode; Response.ContentType = "application/json";
        await Response.WriteAsync(payload, ct);
    }
    [HttpPost("sessions/{id}/rewind")] public Task Rewind(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/rewind", body, ct);
    [HttpPost("sessions/{id}/model")] public Task SetModel(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/model", body, ct);
    [HttpGet("sessions/{id}/messages")] public async Task<IActionResult> Messages(string id, [FromQuery] int limit = 100, [FromQuery] string from_id = null, [FromQuery] string older_before = null, CancellationToken ct = default) {
        var (status, payload) = await MessagesCore(Id(id), limit, from_id, older_before, ct);
        return StatusCode(status, payload);
    }
    private async Task<(int Status, JsonNode? Payload)> MessagesCore(string id, int limit, string from_id, string older_before, CancellationToken ct) {
        var query = $"?limit={Math.Clamp(limit, 1, 500)}" +
            (string.IsNullOrEmpty(from_id) ? "" : "&from_id=" + Uri.EscapeDataString(from_id)) +
            (string.IsNullOrEmpty(older_before) ? "" : "&older_before=" + Uri.EscapeDataString(older_before));
        using var upstream = await bridge.SendAsync(HttpMethod.Get, $"sessions/{id}/messages{query}", null, ct);
        if (!upstream.IsSuccessStatusCode) return ((int)upstream.StatusCode, JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct)));
        var json = JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct));
        json = AgentHistoryWindow.Select(json, limit, from_id, older_before);
        return (200, attachments.AddMessageAttachments(id, json?.ToJsonString() ?? "{}"));
    }
    /// <summary>一次往返取齐打开会话所需的数据：详情、历史、上下文、活跃 run、外部活动、队列。
    /// 单项失败不拖垮整体——失败项为 null，前端按需回退到对应的单独请求。</summary>
    [HttpGet("sessions/{id}/overview")] public async Task<IActionResult> Overview(string id, [FromQuery] int limit = 80, CancellationToken ct = default)
    {
        Response.Headers.CacheControl = "no-store";
        var sessionId = Id(id);
        Task<JsonObject?> Part(Func<CancellationToken, Task<JsonObject?>> work) => AgentOverview.ReadPart(work, ct);
        async Task<JsonObject?> Upstream(string path, CancellationToken? token = null) {
            var requestToken = token ?? ct;
            using var response = await bridge.SendAsync(HttpMethod.Get, path, null, requestToken);
            return response.IsSuccessStatusCode ? await JsonNode.ParseAsync(await response.Content.ReadAsStreamAsync(requestToken), cancellationToken: requestToken) as JsonObject : null;
        }
        var detailTask = Part(token => Upstream($"sessions/{sessionId}", token));
        var contextTask = Part(token => Upstream($"sessions/{sessionId}/context", token));
        var runStateTask = Part(token => Upstream($"sessions/{sessionId}/active-run", token));
        var queueTask = Part(token => Upstream($"sessions/{sessionId}/queue", token));
        var historyTask = Part(async token => {
            var (status, payload) = await MessagesCore(sessionId, Math.Clamp(limit, 1, 100), null, null, token);
            return status == 200 ? payload as JsonObject : null;
        });
        var activityTask = Part(token => ActivityAsync(sessionId, token));
        await Task.WhenAll(detailTask, contextTask, runStateTask, queueTask, historyTask, activityTask);
        JsonObject? run = AgentOverview.RunDocument(runStateTask.Result, sessionId);
        if (run == null && runStateTask.Result?["run_id"]?.ToString() is string runId && Regex.IsMatch(runId, "^[a-zA-Z0-9_-]{1,160}$"))
            run = await Part(token => Upstream($"runs/{runId}", token));
        JsonNode? files = null;
        try { files = JsonSerializer.SerializeToNode(new { data = attachments.List(sessionId) }); }
        catch (Exception) { /* The app can use the existing files endpoint. */ }
        return Ok(new JsonObject {
            ["detail"] = detailTask.Result,
            ["history"] = historyTask.Result,
            ["context"] = contextTask.Result,
            ["active_run"] = runStateTask.Result?.DeepClone(),
            ["run"] = run,
            ["activity"] = activityTask.Result,
            ["queue"] = queueTask.Result,
            ["files"] = files,
        });
    }
    [HttpGet("sessions/{id}/files")] public IActionResult Files(string id) => Ok(new { data = attachments.List(Id(id)) });
    [HttpGet("sessions/{id}/tools/{summaryId}")] public Task ToolDetails(string id, string summaryId, [FromQuery] int offset, CancellationToken ct) =>
        Forward(HttpMethod.Get, $"sessions/{Id(id)}/tools/{Id(summaryId)}?offset={Math.Max(0, offset)}", null, ct);
    [HttpGet("sessions/{id}/queue")] public Task Queue(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/queue", null, ct);
    [HttpPost("sessions/{id}/queue")] public Task QueueAction(string id, [FromBody] JsonElement body, CancellationToken ct) {
        var normalized = System.Text.Json.Nodes.JsonNode.Parse(body.GetRawText())!.AsObject();
        normalized["session_id"] = Id(id);
        var prepared = JsonSerializer.SerializeToElement(normalized);
        if (body.TryGetProperty("action", out var action) && action.GetString() is "add" or "update")
            // key 会拼进 outbox 目录名，必须与 Idempotency-Key 同规则校验，防止路径逃逸。
            prepared = attachments.PrepareCodexRun(prepared,
                body.TryGetProperty("key", out var key) && System.Text.RegularExpressions.Regex.IsMatch(key.GetString() ?? "", "^[a-zA-Z0-9_-]{16,120}$")
                    ? key.GetString()! : Guid.NewGuid().ToString("N"));
        return Forward(HttpMethod.Post, $"sessions/{Id(id)}/queue", prepared, ct);
    }
    [HttpGet("sessions/{id}/activity")] public async Task<IActionResult> SessionActivity(string id, CancellationToken ct) {
        Response.Headers.CacheControl = "no-store";
        return Ok(await ActivityAsync(Id(id), ct));
    }
    private async Task<JsonObject> ActivityAsync(string id, CancellationToken ct) {
        var result = activity.Read(id);
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(4));
        try {
            using var upstream = await bridge.SendAsync(HttpMethod.Get, $"sessions/{id}/desktop-activity", null, timeout.Token);
            if (upstream.IsSuccessStatusCode) {
                var live = JsonNode.Parse(await upstream.Content.ReadAsStringAsync(timeout.Token));
                if (live?["available"]?.GetValue<bool>() == true) {
                    // The desktop IPC is a per-client view: it only knows the turns the desktop app
                    // started, so a turn driven by another client (CLI, VS Code extension, another
                    // app-server) is invisible to it. When the shared rollout/lock verdict says a turn
                    // is running, never let that view downgrade it to idle. Desktop may still confirm
                    // running=true, in which case its activity_id is the more precise one.
                    var localRunning = result["running"]?.GetValue<bool>() == true;
                    var desktopRunning = live["running"]?.GetValue<bool>() == true;
                    if (!localRunning || desktopRunning) {
                        if (live["activity_id"]?.ToString() != result["activity_id"]?.ToString()) {
                            result["progress"] = new JsonArray(); result["event_count"] = 0; result["last_response_at"] = null;
                        }
                        foreach (var field in new[] { "available", "running", "activity_id", "kind", "started_at", "phase_started_at" }) result[field] = live[field]?.DeepClone();
                    }
                    result["observed_only"] = true; result["source"] = "desktop";
                    if (result["running"]?.GetValue<bool>() != true) { result["progress"] = new JsonArray(); result["event_count"] = 0; }
                }
            }
        } catch (Exception error) when (!ct.IsCancellationRequested && error is HttpRequestException or OperationCanceledException or JsonException or InvalidOperationException) { }
        return result;
    }
    [HttpPost("sessions/{id}/files"), RequestSizeLimit(501L * 1024 * 1024), RequestFormLimits(MultipartBodyLengthLimit = 501L * 1024 * 1024)]
    public async Task<IActionResult> Upload(string id, IFormFile file, CancellationToken ct) {
        try { return Ok(await attachments.Upload(Id(id), file, ct)); }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
    }
    [HttpGet("sessions/{id}/files/{fileId}")] public IActionResult Attachment(string id, string fileId) {
        try { var path = attachments.Resolve(Id(id), Id(fileId)); return PhysicalFile(path, HermesAttachments.Mime(path), enableRangeProcessing: true); }
        catch (FileNotFoundException) { return NotFound(new { message = "附件不存在" }); }
    }
    [HttpPost("sessions/{id}/messages/{messageId}/attachments")] public IActionResult Bind(string id, string messageId, [FromBody] JsonElement body) {
        attachments.Bind(Id(id), messageId, body.GetProperty("ids").EnumerateArray().Select(x => x.GetString()!).ToArray()); return Ok(new { success = true });
    }
    [HttpPost("runs")] public async Task Run([FromBody] JsonElement body, CancellationToken ct) {
        var key = Request.Headers["Idempotency-Key"].ToString();
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) { Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = "任务需要唯一标识" }, ct); return; }
        using var upstream = await bridge.SendAsync(HttpMethod.Post, "runs", attachments.PrepareCodexRun(body, key), ct, key, Request.Headers["Last-Event-ID"].ToString());
        var payload = await upstream.Content.ReadAsStringAsync(ct);
        if (upstream.IsSuccessStatusCode) {
            try {
                using var doc = JsonDocument.Parse(payload);
                var runId = doc.RootElement.TryGetProperty("run_id", out var r) ? r.GetString() : null;
                var sessionId = body.TryGetProperty("session_id", out var s) ? s.GetString() : null;
                if (!string.IsNullOrEmpty(runId) && !string.IsNullOrEmpty(sessionId)) runs.Register("codex", sessionId, runId);
            } catch (JsonException) { }
        }
        Response.StatusCode = (int)upstream.StatusCode;
        Response.ContentType = upstream.Content.Headers.ContentType?.ToString() ?? "application/json";
        await Response.WriteAsync(payload, ct);
    }

    /// <summary>多端共享：该会话当前是否有活跃 run。终态自动清除。</summary>
    [HttpGet("sessions/{id}/active-run")] public Task ActiveRun(string id, CancellationToken ct) =>
        Forward(HttpMethod.Get, $"sessions/{Id(id)}/active-run", null, ct);
    [HttpGet("runs/{id}")] public Task Status(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"runs/{Id(id)}", null, ct);
    [HttpGet("runs/lookup")] public async Task Lookup([FromQuery] string key, CancellationToken ct) {
        if (!Regex.IsMatch(key ?? "", "^[a-zA-Z0-9_-]{16,120}$")) { Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = "无效的请求标识" }, ct); return; }
        // The IIS host can read the bridge's durable steering journal even while
        // an older, already-running bridge binary is finishing a desktop task.
        if (SteeringReceipt(key) is { } receipt) { await Response.WriteAsJsonAsync(receipt, ct); return; }
        await Forward(HttpMethod.Get, "runs/lookup?key=" + Uri.EscapeDataString(key), null, ct);
    }
    private static object SteeringReceipt(string key) {
        try {
            var path = Path.Combine(RemoteToolPaths.CodexState, "runs.json");
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var document = JsonDocument.Parse(stream);
            object match = null;
            foreach (var run in document.RootElement.EnumerateObject()) {
                if (!run.Value.TryGetProperty("steering_requests", out var requests) || requests.ValueKind != JsonValueKind.Object ||
                    !requests.TryGetProperty(key, out var saved)) continue;
                if (match != null) return new { found = true, kind = "steering", delivery = "unknown" };
                var status = saved.TryGetProperty("status", out var state) ? state.GetString() : "";
                var session = run.Value.TryGetProperty("session_id", out var id) ? id.GetString() : "";
                match = new { found = true, kind = "steering", run_id = run.Name, session_id = session,
                    status, delivery = status == "accepted" ? "accepted" : status == "rejected" ? "rejected" : "unknown" };
            }
            return match;
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException or InvalidOperationException) { return null; }
    }
    [HttpGet("runs/{id}/events")] public Task Events(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"runs/{Id(id)}/events", null, ct);
    [HttpPost("runs/{id}/stop")] public Task Stop(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/stop", JsonSerializer.SerializeToElement(new { }), ct);
    [HttpPost("runs/{id}/handover")] public Task Handover(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/handover", JsonSerializer.SerializeToElement(new { }), ct);
    [HttpPost("sessions/{id}/handover")] public Task HandoverSession(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/handover", JsonSerializer.SerializeToElement(new { action = "handover" }), ct);
    [HttpPost("runs/{id}/steer")] public Task Steer(string id, [FromBody] JsonElement body, CancellationToken ct) {
        var key = Request.Headers["Idempotency-Key"].ToString();
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) { Response.StatusCode = 400; return Response.WriteAsJsonAsync(new { message = "插话需要唯一请求标识" }, ct); }
        try {
            var prepared = body.TryGetProperty("attachment_ids", out _) ? attachments.PrepareCodexRun(body, key) : body;
            return Forward(HttpMethod.Post, $"runs/{Id(id)}/steer", prepared, ct, key);
        } catch (Exception error) when (error is ArgumentException or FileNotFoundException) {
            Response.StatusCode = 400; return Response.WriteAsJsonAsync(new { message = error.Message }, ct);
        }
    }
    [HttpPost("runs/{id}/approval")] public Task Approve(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/approval", body, ct);
    [HttpGet("accounts")] public Task Accounts(CancellationToken ct) => Forward(HttpMethod.Get, "accounts", null, ct);
    [HttpGet("usage")] public Task Usage(CancellationToken ct) => Forward(HttpMethod.Get, "usage", null, ct);
    [HttpPost("accounts/import")] public Task ImportAccount(CancellationToken ct) => Forward(HttpMethod.Post, "accounts/import", JsonSerializer.SerializeToElement(new { }), ct);
    [HttpPost("accounts/{id}/remove")] public Task RemoveAccount(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"accounts/{Id(id)}/remove", JsonSerializer.SerializeToElement(new { }), ct);
    [HttpPost("accounts/{id}/use")] public Task Account(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"accounts/{Id(id)}/use", JsonSerializer.SerializeToElement(new { }), ct);
    [HttpPost("accounts/login")] public Task Login([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "accounts/login", body, ct);
    [HttpGet("accounts/login-status")] public Task LoginStatus(CancellationToken ct) => Forward(HttpMethod.Get, "accounts/login-status", null, ct);
    [HttpGet("workspaces")] public Task Workspaces(CancellationToken ct) => Forward(HttpMethod.Get, "workspaces", null, ct);
    [HttpGet("workspaces/{id}/usage")] public Task WorkspaceUsage(string id, [FromQuery] bool refresh, CancellationToken ct) => Forward(HttpMethod.Get, $"workspaces/{Id(id)}/usage" + (refresh ? "?refresh=1" : ""), null, ct);
    [HttpGet("workspaces/{id}/rate-limit-resets")] public async Task<IActionResult> WorkspaceRateLimitResets(string id, CancellationToken ct) {
        Response.Headers.CacheControl = "no-store";
        try { return Ok(await bridge.WorkspaceResetCreditDetails(Id(id), ct)); }
        catch (RemoteTool.WebApi.Services.Codex.CodexError error) { return StatusCode(error.Status, new { message = error.Message }); }
        catch (Exception error) when (!ct.IsCancellationRequested && error is HttpRequestException or OperationCanceledException) {
            return StatusCode(502, new { message = "额度重置详情查询失败，请稍后刷新重试" });
        }
    }
    [HttpPost("workspaces/{id}/rate-limit-resets/consume")] public Task ConsumeWorkspaceRateLimitReset(string id, [FromBody] JsonElement body, CancellationToken ct) =>
        Forward(HttpMethod.Post, $"workspaces/{Id(id)}/rate-limit-resets/consume", body, ct, Request.Headers["Idempotency-Key"].ToString());
    [HttpPost("workspaces")] public Task SaveWorkspace([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "workspaces", body, ct);
    [HttpPost("workspaces/{id}/use")] public Task Workspace(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"workspaces/{Id(id)}/use", JsonSerializer.SerializeToElement(new { }), ct);

    private async Task Forward(HttpMethod method, string path, JsonElement? body, CancellationToken ct, string key = null) {
        Response.Headers.CacheControl = "no-store";
        try {
            using var upstream = await bridge.SendAsync(method, path, body, ct, key, Request.Headers["Last-Event-ID"].ToString());
            Response.StatusCode = (int)upstream.StatusCode;
            Response.ContentType = upstream.Content.Headers.ContentType?.ToString() ?? "application/json";
            if (Response.ContentType.StartsWith("text/event-stream")) {
                HttpContext.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpResponseBodyFeature>()?.DisableBuffering();
                using var reader = new StreamReader(await upstream.Content.ReadAsStreamAsync(ct));
                while (await reader.ReadLineAsync(ct) is { } line) { await Response.WriteAsync(line + "\n", ct); await Response.Body.FlushAsync(ct); }
            } else if ((path == "sessions" || path.StartsWith("sessions?", StringComparison.Ordinal)) && upstream.IsSuccessStatusCode) {
                var payload = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!.AsObject();
                foreach (var row in payload["data"]?.AsArray() ?? new System.Text.Json.Nodes.JsonArray()) {
                    var timestamp = activity.LastMessageTime(row?["id"]?.ToString() ?? "");
                    if (row != null) row["last_active"] = timestamp > 0 ? System.Text.Json.Nodes.JsonValue.Create(timestamp) : null;
                }
                await Response.WriteAsJsonAsync(payload, ct);
            } else if (path == "capabilities" && upstream.IsSuccessStatusCode) {
                var payload = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!.AsObject();
                payload["chuckie_features"] = new System.Text.Json.Nodes.JsonObject { ["external_session_activity"] = true };
                payload["web_state_version"] = 3;
                await Response.WriteAsJsonAsync(payload, ct);
            } else await upstream.Content.CopyToAsync(Response.Body, ct);
        } catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
        catch (Exception error) when (!Response.HasStarted && error is InvalidOperationException or HttpRequestException or IOException) {
            Response.StatusCode = 503;
            await Response.WriteAsJsonAsync(new { message = error is InvalidOperationException ? error.Message : "无法访问本机 Codex" }, ct);
        }
    }
}
