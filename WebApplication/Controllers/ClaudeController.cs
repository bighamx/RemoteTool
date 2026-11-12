using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Controllers;

/// <summary>Claude Code endpoints with the same contract as /api/codex, served by the local SDK bridge.</summary>
[ApiController, Authorize, Route("api/claude")]
public sealed class ClaudeController(ClaudeBridge bridge, [FromKeyedServices("claude")] HermesAttachments attachments, RunRegistry runs) : ControllerBase
{
    private const string Agent = "claude";
    private static string Id(string value) => Regex.IsMatch(value ?? "", "^[a-zA-Z0-9_-]{1,160}$") ? value : throw new ArgumentException("无效会话或任务标识");
    private static bool ValidKey(string key) => Regex.IsMatch(key ?? "", "^[a-zA-Z0-9_-]{16,120}$");
    private string Key => Request.Headers["Idempotency-Key"].ToString();

    [HttpGet("capabilities")] public Task Capabilities(CancellationToken ct) => Forward(HttpMethod.Get, "capabilities", null, ct);
    [HttpGet("setup")] public Task Setup([FromQuery] bool fresh, CancellationToken ct) => Forward(HttpMethod.Get, "setup" + (fresh ? "?fresh=true" : ""), null, ct);
    [HttpGet("sessions")] public Task Sessions([FromQuery] string cursor, CancellationToken ct) =>
        Forward(HttpMethod.Get, "sessions" + (string.IsNullOrEmpty(cursor) ? "" : "?cursor=" + Uri.EscapeDataString(cursor)), null, ct);
    [HttpPost("sessions")] public Task Create([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "sessions", body.GetRawText(), ct);
    [HttpGet("sessions/{id}")] public Task Session(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}", null, ct);
    [HttpPatch("sessions/{id}")] public Task Rename(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Patch, $"sessions/{Id(id)}", body.GetRawText(), ct);
    [HttpPost("sessions/{id}/pin")] public Task Pin(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/pin", body.GetRawText(), ct);
    [HttpPost("sessions/{id}/delete")] public Task Delete(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/delete", "{}", ct);
    [HttpPost("sessions/{id}/fork")] public Task Fork(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/fork", body.GetRawText(), ct);
    [HttpPost("sessions/{id}/rewind")] public Task Rewind(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/rewind", body.GetRawText(), ct);
    [HttpPost("sessions/{id}/takeover")] public Task Takeover(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/takeover", body.GetRawText(), ct);
    [HttpPost("sessions/{id}/rewind/cancel")] public Task CancelRewind(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/rewind/cancel", "{}", ct);
    [HttpPost("sessions/{id}/model")] public Task Model(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/model", body.GetRawText(), ct);
    [HttpGet("sessions/{id}/permissions")] public Task Permissions(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/permissions", null, ct);
    [HttpPost("sessions/{id}/permissions")] public Task SetPermissions(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"sessions/{Id(id)}/permissions", body.GetRawText(), ct);
    [HttpGet("sessions/{id}/context")] public Task Context(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/context", null, ct);
    [HttpGet("sessions/{id}/activity")] public Task Activity(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/activity", null, ct);
    [HttpGet("sessions/{id}/tools/{summaryId}")] public Task Tools(string id, string summaryId, [FromQuery] int offset, CancellationToken ct) =>
        Forward(HttpMethod.Get, $"sessions/{Id(id)}/tools/{Id(summaryId)}?offset={Math.Max(0, offset)}", null, ct);
    [HttpGet("model-options")] public Task Models([FromQuery] string session, CancellationToken ct) =>
        Forward(HttpMethod.Get, "model-options" + (string.IsNullOrEmpty(session) ? "" : "?session=" + Id(session)), null, ct);
    [HttpGet("usage")] public Task Usage(CancellationToken ct) => Forward(HttpMethod.Get, "usage", null, ct);

    [HttpPost("sessions/{id}/compact")] public Task Compact(string id, CancellationToken ct) {
        if (!ValidKey(Key)) return Reject(400, "压缩需要唯一请求标识");
        return Forward(HttpMethod.Post, $"sessions/{Id(id)}/compact", "{}", ct, Key, register: Id(id));
    }

    [HttpGet("sessions/{id}/messages")]
    public async Task<IActionResult> Messages(string id, [FromQuery] int limit = 80, [FromQuery] string older_before = null, CancellationToken ct = default) {
        Response.Headers.CacheControl = "no-store";
        var session = Id(id);
        var query = $"?limit={Math.Clamp(limit, 1, 500)}" + (string.IsNullOrEmpty(older_before) ? "" : "&older_before=" + Uri.EscapeDataString(older_before));
        try {
            using var upstream = await bridge.SendAsync(HttpMethod.Get, $"sessions/{session}/messages{query}", null, ct);
            var text = await upstream.Content.ReadAsStringAsync(ct);
            if (!upstream.IsSuccessStatusCode) return new ContentResult { StatusCode = (int)upstream.StatusCode, ContentType = "application/json; charset=utf-8", Content = text };
            var json = attachments.AddMessageAttachments(session, text);
            // User rows name their uploaded files by path; expose the same metadata as Codex rows.
            foreach (var row in json["data"]?.AsArray() ?? new JsonArray()) {
                if (row?["attachment_paths"] is not JsonArray paths) continue;
                var files = new JsonArray();
                foreach (var path in paths) {
                    try { files.Add(JsonSerializer.SerializeToNode(attachments.Metadata(session, path!.GetValue<string>()))); }
                    catch (Exception e) when (e is FileNotFoundException or ArgumentException or IOException or InvalidOperationException) { }
                }
                row["attachments"] = files; ((JsonObject)row).Remove("attachment_paths");
            }
            return Content(json.ToJsonString(), "application/json; charset=utf-8");
        } catch (Exception e) when (e is InvalidOperationException or HttpRequestException or IOException) {
            return StatusCode(503, new { message = e is InvalidOperationException ? e.Message : "Claude 本机接口暂不可用" });
        }
    }

    [HttpGet("sessions/{id}/active-run")]
    public async Task ActiveRun(string id, CancellationToken ct) {
        var session = Id(id);
        await Forward(HttpMethod.Get, $"sessions/{session}/active-run", null, ct, inspect: payload => {
            // The bridge is authoritative: an empty answer clears the shared registry entry.
            if (string.IsNullOrEmpty(payload?["run_id"]?.ToString()) && runs.Query(Agent, session) is { Length: > 0 } registered) runs.Clear(Agent, session, registered);
        });
    }

    [HttpGet("sessions/{id}/files")] public IActionResult Files(string id) => Ok(new { data = attachments.List(Id(id)) });
    [HttpPost("sessions/{id}/files"), RequestSizeLimit(501L * 1024 * 1024), RequestFormLimits(MultipartBodyLengthLimit = 501L * 1024 * 1024)]
    public async Task<IActionResult> Upload(string id, IFormFile file, CancellationToken ct) {
        try { return Ok(await attachments.Upload(Id(id), file, ct)); }
        catch (ArgumentException error) { return BadRequest(new { message = error.Message }); }
    }
    [HttpGet("sessions/{id}/files/{fileId}")] public IActionResult Attachment(string id, string fileId) {
        try { var path = attachments.Resolve(Id(id), Id(fileId)); return PhysicalFile(path, HermesAttachments.Mime(path), enableRangeProcessing: true); }
        catch (FileNotFoundException) { return NotFound(new { message = "附件不存在" }); }
    }

    [HttpPost("runs")] public Task Run([FromBody] JsonElement body, CancellationToken ct) {
        if (!ValidKey(Key)) return Reject(400, "任务需要唯一标识");
        string prepared;
        try { prepared = Prepare(body); }
        catch (Exception e) when (e is ArgumentException or FileNotFoundException or KeyNotFoundException or InvalidOperationException) { return Reject(400, e.Message); }
        var session = body.TryGetProperty("session_id", out var s) && s.ValueKind == JsonValueKind.String ? Id(s.GetString()!) : null;
        return Forward(HttpMethod.Post, "runs", prepared, ct, Key, register: session);
    }
    [HttpPost("runs/{id}/steer")] public Task Steer(string id, [FromBody] JsonElement body, CancellationToken ct) {
        if (!ValidKey(Key)) return Reject(400, "插话需要唯一请求标识");
        string prepared;
        try { prepared = Prepare(body); }
        catch (Exception e) when (e is ArgumentException or FileNotFoundException or KeyNotFoundException or InvalidOperationException) { return Reject(400, e.Message); }
        return Forward(HttpMethod.Post, $"runs/{Id(id)}/steer", prepared, ct, Key);
    }
    /// <summary>Resolves uploaded attachment ids to local paths; ids never reach the model.</summary>
    private string Prepare(JsonElement body) {
        var node = JsonNode.Parse(body.GetRawText())!.AsObject();
        var ids = node["attachment_ids"] as JsonArray ?? new JsonArray();
        if (ids.Count > 8) throw new ArgumentException("每条消息最多 8 个附件");
        if (ids.Count > 0) {
            var session = Id(node["session_id"]?.GetValue<string>() ?? throw new ArgumentException("缺少会话标识"));
            node["attachment_paths"] = new JsonArray(ids.Select(value => (JsonNode)JsonValue.Create(attachments.Resolve(session, Id(value!.GetValue<string>())))!).ToArray());
        }
        node.Remove("attachment_ids");
        return node.ToJsonString();
    }
    [HttpGet("runs/lookup")] public Task Lookup([FromQuery] string key, CancellationToken ct) =>
        ValidKey(key) ? Forward(HttpMethod.Get, "runs/lookup?key=" + Uri.EscapeDataString(key), null, ct) : Reject(400, "无效的请求标识");
    [HttpGet("runs/{id}")] public Task Status(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"runs/{Id(id)}", null, ct);
    [HttpGet("runs/{id}/events")] public Task Events(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"runs/{Id(id)}/events", null, ct, stream: true);
    [HttpPost("runs/{id}/approval")] public Task Approval(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/approval", body.GetRawText(), ct);
    [HttpPost("runs/{id}/stop")] public Task Stop(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/stop", "{}", ct);
    [HttpPost("runs/{id}/acknowledge")] public Task Acknowledge(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/acknowledge", "{}", ct);

    private Task Reject(int status, string message) {
        Response.StatusCode = status;
        return Response.WriteAsJsonAsync(new { message, delivery = "rejected" });
    }

    private async Task Forward(HttpMethod method, string path, string body, CancellationToken ct, string key = null, bool stream = false, string register = null, Action<JsonNode> inspect = null) {
        Response.Headers.CacheControl = "no-store";
        try {
            var lastEvent = Request.Headers["Last-Event-ID"].ToString();
            using var upstream = await bridge.SendAsync(method, path, body, ct, key, stream ? lastEvent : null, stream);
            Response.StatusCode = (int)upstream.StatusCode;
            Response.ContentType = upstream.Content.Headers.ContentType?.ToString() ?? "application/json; charset=utf-8";
            if (Response.ContentType.StartsWith("text/event-stream")) {
                HttpContext.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpResponseBodyFeature>()?.DisableBuffering();
                using var reader = new StreamReader(await upstream.Content.ReadAsStreamAsync(ct));
                while (await reader.ReadLineAsync(ct) is { } line) { await Response.WriteAsync(line + "\n", ct); if (line.Length == 0) await Response.Body.FlushAsync(ct); }
                return;
            }
            var text = await upstream.Content.ReadAsStringAsync(ct);
            if (upstream.IsSuccessStatusCode && (register != null || inspect != null)) {
                try {
                    var payload = JsonNode.Parse(text);
                    inspect?.Invoke(payload);
                    if (register != null && payload?["run_id"]?.ToString() is { Length: > 0 } run) runs.Register(Agent, register, run);
                } catch (JsonException) { }
            }
            await Response.WriteAsync(text, ct);
        } catch (OperationCanceledException) when (ct.IsCancellationRequested) { }
        catch (Exception error) when (!Response.HasStarted && error is InvalidOperationException or HttpRequestException or IOException or OperationCanceledException or System.ComponentModel.Win32Exception) {
            Response.StatusCode = 503;
            // A POST that may have reached the worker has an unknown outcome; clients verify by receipt.
            await Response.WriteAsJsonAsync(new {
                message = error is InvalidOperationException ? error.Message : method == HttpMethod.Get ? "Claude 本机接口暂不可用" : "Claude 本机接口未响应；若正在发送，请先核对发送状态",
                delivery = error is InvalidOperationException ? "rejected" : method == HttpMethod.Get ? "rejected" : "unknown" });
        }
    }
}
