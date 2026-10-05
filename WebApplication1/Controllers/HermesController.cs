using ChuckieHelper.WebApi.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace ChuckieHelper.WebApi.Controllers;

[ApiController, Authorize, Route("api/hermes")]
public sealed class HermesController(HermesBridge bridge, HermesManagement management, HermesAttachments attachments, HermesCompaction compaction, RunRegistry runs, IConfiguration configuration) : ControllerBase
{
    private static string Id(string value) => Regex.IsMatch(value, "^[a-zA-Z0-9_-]{1,160}$") ? value : throw new ArgumentException("无效的会话或任务标识");
    [HttpGet("capabilities")] public async Task<IActionResult> Capabilities(CancellationToken ct) {
        try {
        using var upstream = await bridge.SendAsync(HttpMethod.Get, "v1/capabilities", null, null, ct);
        if (!upstream.IsSuccessStatusCode) return StatusCode(502, new { message = "无法读取 Hermes 能力" });
        var result = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!.AsObject();
        result["chuckie_features"] = new System.Text.Json.Nodes.JsonObject { ["attachment_steering"] = true };
        return Ok(result);
        } catch (Exception error) when (error is InvalidOperationException or HttpRequestException or IOException) {
            return StatusCode(503, new { message = error is InvalidOperationException ? error.Message : "无法访问本机 Hermes" });
        }
    }
    [HttpGet("sessions/{id}/context")] public async Task<IActionResult> SessionContext(string id, CancellationToken ct) {
        try { return Ok(await management.Invoke("session_context", JsonSerializer.SerializeToElement(new { session_id = Id(id) }), ct)); }
        catch (InvalidOperationException) { return Ok(new { available = false }); }
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
        var info = await management.Invoke("model_info", null, ct);
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
    [HttpGet("sessions")] public async Task<IActionResult> Sessions([FromQuery] int offset = 0, CancellationToken ct = default) {
        using var upstream = await bridge.SendAsync(HttpMethod.Get, $"api/sessions?limit=50&offset={Math.Max(0, offset)}", null, null, ct);
        if (!upstream.IsSuccessStatusCode) return StatusCode(502, new { message = "无法读取 Hermes 会话列表" });
        var result = System.Text.Json.Nodes.JsonNode.Parse(await upstream.Content.ReadAsStringAsync(ct))!;
        if (result["data"] is System.Text.Json.Nodes.JsonArray rows) {
            var key = configuration["Hermes:KeyFile"] ?? Environment.GetEnvironmentVariable("HERMES_API_KEY_FILE")
                ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "hermes", ".env");
            var previews = LatestSessionPreview.Read(Path.Combine(Path.GetDirectoryName(Path.GetFullPath(key))!, "state.db"), rows.Select(row => row?["id"]?.ToString()), false);
            foreach (var row in rows.OfType<System.Text.Json.Nodes.JsonObject>()) {
                if (previews.TryGetValue(row["id"]!.ToString(), out var text)) { row["latest_user_message"] = text; row["preview"] = text; }
            }
        }
        return Ok(result);
    }
    [HttpPost("sessions")] public Task CreateSession([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "api/sessions", body, null, ct);
    [HttpGet("sessions/{id}")] public Task SessionInfo(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"api/sessions/{Id(id)}", null, null, ct);
    [HttpPatch("sessions/{id}")] public Task RenameSession(string id, [FromBody] JsonElement body, CancellationToken ct)
    {
        var title = body.GetProperty("title").GetString()?.Trim();
        if (string.IsNullOrEmpty(title) || title.Length > 160) {
            Response.StatusCode = 400;
            return Response.WriteAsJsonAsync(new { message = "会话名称应为 1–160 个字符" }, ct);
        }
        return Forward(HttpMethod.Patch, $"api/sessions/{Id(id)}", JsonSerializer.SerializeToElement(new { title }), null, ct);
    }
    [HttpDelete("sessions/{id}"), HttpPost("sessions/{id}/delete")] public Task DeleteSession(string id, CancellationToken ct) => Forward(HttpMethod.Delete, $"api/sessions/{Id(id)}", null, null, ct);
    [HttpGet("sessions/{id}/messages")] public async Task<IActionResult> Messages(string id, CancellationToken ct)
    {
        using var upstream = await bridge.SendAsync(HttpMethod.Get, $"api/sessions/{Id(id)}/messages?inline_images=false", null, null, ct);
        if (!upstream.IsSuccessStatusCode) return StatusCode(502, new { message = "无法读取 Hermes 会话历史" });
        return Ok(attachments.AddMessageAttachments(id, await upstream.Content.ReadAsStringAsync(ct)));
    }
    [HttpPost("sessions/{id}/messages/{messageId}/attachments")] public IActionResult BindAttachments(string id, string messageId, [FromBody] JsonElement body)
    { attachments.Bind(Id(id), messageId, body.GetProperty("ids").EnumerateArray().Select(value => value.GetString()!).ToArray()); return Ok(new { success = true }); }
    [HttpPost("runs")] public async Task Run([FromBody] JsonElement body, CancellationToken ct)
    {
        var key = Request.Headers["Idempotency-Key"].ToString();
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) { Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = "任务提交需要唯一的 Idempotency-Key" }, ct); return; }
        try
        {
            using var upstream = await bridge.SendAsync(HttpMethod.Post, "v1/runs", attachments.PrepareRun(body, key), key, ct, Request.Headers["Last-Event-ID"].ToString());
            Response.Headers.CacheControl = "no-store";
            var payload = await upstream.Content.ReadAsStringAsync(ct);
            if (!upstream.IsSuccessStatusCode)
            {
                Response.StatusCode = upstream.StatusCode == System.Net.HttpStatusCode.Unauthorized ? 502 : (int)upstream.StatusCode;
                await Response.WriteAsJsonAsync(new { message = $"Hermes 请求失败（HTTP {(int)upstream.StatusCode}）" }, ct);
                return;
            }
            // 登记「会话当前活跃 run」供多端共享（平板打开同一会话可挂载实时进度）。
            try
            {
                using var doc = JsonDocument.Parse(payload);
                var runId = doc.RootElement.TryGetProperty("run_id", out var r) ? r.GetString() : null;
                var sessionId = body.TryGetProperty("session_id", out var s) ? s.GetString() : null;
                if (!string.IsNullOrEmpty(runId) && !string.IsNullOrEmpty(sessionId)) runs.Register("hermes", sessionId, runId);
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
        var sessionId = Id(id);
        var runId = runs.Query("hermes", sessionId);
        if (runId == null) { await Response.WriteAsJsonAsync(new { run_id = (string?)null }, ct); return; }
        // 校验 Hermes 侧真实状态：终态（含 404）即清除登记，避免幽灵 run。
        try
        {
            using var upstream = await bridge.SendAsync(HttpMethod.Get, $"v1/runs/{runId}", null, null, ct, null);
            if (upstream.StatusCode == System.Net.HttpStatusCode.NotFound)
            {
                runs.Clear("hermes", sessionId, runId);
                await Response.WriteAsJsonAsync(new { run_id = (string?)null }, ct);
                return;
            }
            if (upstream.IsSuccessStatusCode)
            {
                using var doc = JsonDocument.Parse(await upstream.Content.ReadAsStringAsync(ct));
                var status = doc.RootElement.TryGetProperty("status", out var s) ? s.GetString() : "";
                if (status is "completed" or "failed" or "cancelled" or "interrupted")
                {
                    runs.Clear("hermes", sessionId, runId);
                    await Response.WriteAsJsonAsync(new { run_id = (string?)null }, ct);
                    return;
                }
            }
        }
        catch (Exception) when (!ct.IsCancellationRequested) { /* 校验失败保守保留登记 */ }
        await Response.WriteAsJsonAsync(new { run_id = runId }, ct);
    }
    [HttpGet("runs/{id}")] public async Task Status(string id, CancellationToken ct) {
        if (!Id(id).StartsWith("hcompact_")) { await Forward(HttpMethod.Get, $"v1/runs/{id}", null, null, ct); return; }
        try { await Response.WriteAsJsonAsync(compaction.Status(id), ct); }
        catch (KeyNotFoundException) { Response.StatusCode = 404; await Response.WriteAsJsonAsync(new { message = "压缩任务不存在" }, ct); }
    }
    [HttpGet("sessions/{id}/files")] public IActionResult Files(string id) => Ok(new { data = attachments.List(Id(id)) });
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
        try { var path = attachments.Resolve(Id(id), Id(fileId)); return PhysicalFile(path, HermesAttachments.Mime(path), enableRangeProcessing: true); }
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
        if (body.TryGetProperty("attachment_ids", out var ids) && ids.GetArrayLength() > 0) {
            var key = Request.Headers["Idempotency-Key"].ToString();
            if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) { Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = "插话需要唯一请求标识" }, ct); return; }
            try {
                using var status = await bridge.SendAsync(HttpMethod.Get, $"v1/runs/{runId}", null, null, ct);
                if (!status.IsSuccessStatusCode) { Response.StatusCode = (int)status.StatusCode; await Response.WriteAsJsonAsync(new { message = "无法核对当前 Hermes 任务" }, ct); return; }
                using var doc = JsonDocument.Parse(await status.Content.ReadAsStringAsync(ct));
                if (!doc.RootElement.TryGetProperty("session_id", out var field) || string.IsNullOrEmpty(field.GetString())) {
                    Response.StatusCode = 409; await Response.WriteAsJsonAsync(new { message = "当前任务未返回会话标识，无法核对附件归属" }, ct); return;
                }
                var session = field.GetString()!;
                body = attachments.PrepareHermesSteer(body, session, key);
            } catch (Exception error) when (error is ArgumentException or FileNotFoundException) {
                Response.StatusCode = 400; await Response.WriteAsJsonAsync(new { message = error.Message }, ct); return;
            }
        }
        await Forward(HttpMethod.Post, $"v1/runs/{runId}/steer", body, null, ct);
    }
    [HttpPost("runs/{id}/approval")] public Task Approval(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"v1/runs/{Id(id)}/approval", body, null, ct);
    [HttpPost("responses")] public Task Responses([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "v1/responses", body, null, ct);

    private async Task Forward(HttpMethod method, string path, JsonElement? body, string? key, CancellationToken ct)
    {
        try
        {
            using var upstream = await bridge.SendAsync(method, path, body, key, ct, Request.Headers["Last-Event-ID"].ToString());
            Response.Headers.CacheControl = "no-store";
            if (!upstream.IsSuccessStatusCode)
            {
                Response.StatusCode = upstream.StatusCode == System.Net.HttpStatusCode.Unauthorized ? 502 : (int)upstream.StatusCode;
                await Response.WriteAsJsonAsync(new { message = $"Hermes 请求失败（HTTP {(int)upstream.StatusCode}）" }, ct);
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
