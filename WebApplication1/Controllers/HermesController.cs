using ChuckieHelper.WebApi.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace ChuckieHelper.WebApi.Controllers;

[ApiController, Authorize, Route("api/hermes")]
public sealed class HermesController(HermesBridge bridge, HermesManagement management, HermesAttachments attachments) : ControllerBase
{
    private static string Id(string value) => Regex.IsMatch(value, "^[a-zA-Z0-9_-]{1,160}$") ? value : throw new ArgumentException("无效的会话或任务标识");
    [HttpGet("capabilities")] public Task Capabilities(CancellationToken ct) => Forward(HttpMethod.Get, "v1/capabilities", null, null, ct);
    [HttpGet("models")] public Task Models(CancellationToken ct) => Forward(HttpMethod.Get, "v1/models", null, null, ct);
    [HttpGet("model-options")] public Task ModelOptions(CancellationToken ct) => Forward(HttpMethod.Get, "api/model/options", null, null, ct);
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
    [HttpGet("sessions")] public Task Sessions([FromQuery] int offset = 0, CancellationToken ct = default) => Forward(HttpMethod.Get, $"api/sessions?limit=50&offset={Math.Max(0, offset)}", null, null, ct);
    [HttpPost("sessions")] public Task CreateSession([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "api/sessions", body, null, ct);
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
    [HttpPost("runs")] public Task Run([FromBody] JsonElement body, CancellationToken ct)
    {
        var key = Request.Headers["Idempotency-Key"].ToString();
        if (!Regex.IsMatch(key, "^[a-zA-Z0-9_-]{16,120}$")) { Response.StatusCode = 400; return Response.WriteAsJsonAsync(new { message = "任务提交需要唯一的 Idempotency-Key" }, ct); }
        try { return Forward(HttpMethod.Post, "v1/runs", attachments.PrepareRun(body, key), key, ct); }
        catch (Exception error) when (error is ArgumentException or FileNotFoundException) { Response.StatusCode = 400; return Response.WriteAsJsonAsync(new { message = error.Message }, ct); }
    }
    [HttpGet("runs/{id}")] public Task Status(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"v1/runs/{Id(id)}", null, null, ct);
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
    [HttpGet("runs/{id}/events")] public Task Events(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"v1/runs/{Id(id)}/events", null, null, ct);
    [HttpPost("runs/{id}/stop")] public Task Stop(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"v1/runs/{Id(id)}/stop", null, null, ct);
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
