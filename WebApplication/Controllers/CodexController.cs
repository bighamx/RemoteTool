using RemoteTool.WebApi.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Authorize, Route("api/codex")]
public sealed class CodexController(CodexBridge bridge, [FromKeyedServices("codex")] HermesAttachments attachments, RunRegistry runs, CodexSessionActivity activity) : ControllerBase
{
    private static string Id(string value) => Regex.IsMatch(value, "^[a-zA-Z0-9_-]{1,160}$") ? value : throw new ArgumentException("无效会话或任务标识");
    [HttpGet("capabilities")] public Task Capabilities(CancellationToken ct) => Forward(HttpMethod.Get, "capabilities", null, ct);
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
    [HttpPost("sessions")] public Task Create([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "sessions", body, ct);
    [HttpGet("sessions/{id}")] public Task SessionInfo(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}", null, ct);
    [HttpGet("sessions/{id}/context")] public Task Context(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/context", null, ct);
    [HttpPost("sessions/{id}/takeover")] public Task Takeover(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/takeover", body, ct);
    [HttpPost("sessions/{id}/compact")] public Task Compact(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/compact", JsonSerializer.SerializeToElement(new { }), ct, Request.Headers["Idempotency-Key"].ToString());
    [HttpPatch("sessions/{id}")] public Task Rename(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Patch, $"sessions/{Id(id)}", body, ct);
    [HttpPost("sessions/{id}/delete")] public Task Delete(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/delete", JsonSerializer.SerializeToElement(new { }), ct);
    [HttpPost("sessions/{id}/pin")] public Task Pin(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/pin", body, ct);
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
    [HttpGet("sessions/{id}/messages")] public async Task<IActionResult> Messages(string id, [FromQuery] int limit = 30, CancellationToken ct = default) {
        using var upstream = await bridge.SendAsync(HttpMethod.Get, $"sessions/{Id(id)}/messages", null, ct);
        if (!upstream.IsSuccessStatusCode) return StatusCode((int)upstream.StatusCode, System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct)));
        var json = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct));
        var rows = json?["data"] as System.Text.Json.Nodes.JsonArray;
        if (rows != null) {
            var keep = Math.Clamp(limit, 1, 500);
            json["data"] = new System.Text.Json.Nodes.JsonArray(rows.Skip(Math.Max(0, rows.Count - keep)).Select(row => row?.DeepClone()).ToArray());
        }
        return Ok(attachments.AddMessageAttachments(id, json?.ToJsonString() ?? "{}"));
    }
    [HttpGet("sessions/{id}/files")] public IActionResult Files(string id) => Ok(new { data = attachments.List(Id(id)) });
    [HttpGet("sessions/{id}/queue")] public Task Queue(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/queue", null, ct);
    [HttpPost("sessions/{id}/queue")] public Task QueueAction(string id, [FromBody] JsonElement body, CancellationToken ct) {
        var normalized = System.Text.Json.Nodes.JsonNode.Parse(body.GetRawText())!.AsObject();
        normalized["session_id"] = Id(id);
        var prepared = JsonSerializer.SerializeToElement(normalized);
        if (body.TryGetProperty("action", out var action) && action.GetString() is "add" or "update")
            prepared = attachments.PrepareCodexRun(prepared, body.TryGetProperty("key", out var key) ? key.GetString() : Guid.NewGuid().ToString());
        return Forward(HttpMethod.Post, $"sessions/{Id(id)}/queue", prepared, ct);
    }
    [HttpGet("sessions/{id}/activity")] public async Task<IActionResult> SessionActivity(string id, CancellationToken ct) {
        Response.Headers.CacheControl = "no-store";
        var result = activity.Read(Id(id));
        using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct);
        timeout.CancelAfter(TimeSpan.FromSeconds(4));
        try {
            using var upstream = await bridge.SendAsync(HttpMethod.Get, $"sessions/{Id(id)}/desktop-activity", null, timeout.Token);
            if (upstream.IsSuccessStatusCode) {
                var live = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(timeout.Token));
                if (live?["available"]?.GetValue<bool>() == true) {
                    if (live["activity_id"]?.ToString() != result["activity_id"]?.ToString()) {
                        result["progress"] = new System.Text.Json.Nodes.JsonArray(); result["event_count"] = 0; result["last_response_at"] = null;
                    }
                    foreach (var field in new[] { "available", "running", "activity_id", "kind", "started_at", "phase_started_at" }) result[field] = live[field]?.DeepClone();
                    result["observed_only"] = true; result["source"] = "desktop";
                    if (result["running"]?.GetValue<bool>() != true) { result["progress"] = new System.Text.Json.Nodes.JsonArray(); result["event_count"] = 0; }
                }
            }
        } catch (Exception error) when (!ct.IsCancellationRequested && error is HttpRequestException or OperationCanceledException or JsonException or InvalidOperationException) { }
        return Ok(result);
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
    [HttpGet("runs/lookup")] public Task Lookup([FromQuery] string key, CancellationToken ct) => Forward(HttpMethod.Get, "runs/lookup?key=" + Uri.EscapeDataString(Id(key)), null, ct);
    [HttpGet("runs/{id}/events")] public Task Events(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"runs/{Id(id)}/events", null, ct);
    [HttpPost("runs/{id}/stop")] public Task Stop(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/stop", JsonSerializer.SerializeToElement(new { }), ct);
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
    [HttpGet("workspaces/{id}/rate-limit-resets")] public Task WorkspaceRateLimitResets(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"workspaces/{Id(id)}/rate-limit-resets", null, ct);
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
