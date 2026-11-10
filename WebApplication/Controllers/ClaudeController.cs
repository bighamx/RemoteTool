using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Authorize, Route("api/claude")]
public sealed class ClaudeController(ClaudeBridge bridge) : ControllerBase
{
    private static string Id(string value) => Regex.IsMatch(value ?? "", "^[a-zA-Z0-9_-]{1,160}$") ? value : throw new BadHttpRequestException("无效标识");
    private async Task<IActionResult> Forward(HttpMethod method, string path, JsonElement? body, CancellationToken ct) {
        Response.Headers.CacheControl = "no-store";
        try {
            var (status, json) = await bridge.Send(method, path, body?.GetRawText(), Request.Headers["Idempotency-Key"].ToString(), ct);
            return new ContentResult {StatusCode = status, ContentType = "application/json; charset=utf-8", Content = json};
        } catch (Exception e) when (e is InvalidOperationException or IOException or HttpRequestException or OperationCanceledException) {
            return StatusCode(503, new {message = e is InvalidOperationException ? e.Message : "Claude 桥接暂不可用；若正在发送，请先核对结果", delivery = method == HttpMethod.Post ? "unknown" : "rejected"});
        }
    }
    [HttpGet("setup")] public Task<IActionResult> Setup(CancellationToken ct) => Forward(HttpMethod.Get, "setup", null, ct);
    [HttpGet("sessions")] public Task<IActionResult> Sessions(CancellationToken ct) => Forward(HttpMethod.Get, "sessions", null, ct);
    [HttpPost("sessions")] public Task<IActionResult> Create([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "sessions", body, ct);
    [HttpGet("sessions/{id}/messages")] public Task<IActionResult> Messages(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/messages", null, ct);
    [HttpGet("sessions/{id}/active-run")] public Task<IActionResult> Active(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"sessions/{Id(id)}/active-run", null, ct);
    [HttpPost("runs")] public Task<IActionResult> Run([FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, "runs", body, ct);
    [HttpGet("receipts/{key}")] public Task<IActionResult> Receipt(string key, CancellationToken ct) => Forward(HttpMethod.Get, $"receipts/{Id(key)}", null, ct);
    [HttpGet("runs/{id}")] public Task<IActionResult> Status(string id, CancellationToken ct) => Forward(HttpMethod.Get, $"runs/{Id(id)}", null, ct);
    [HttpGet("runs/{id}/events")] public Task<IActionResult> Events(string id, [FromQuery] long after, CancellationToken ct) => after < 0 ? Task.FromResult<IActionResult>(BadRequest()) : Forward(HttpMethod.Get, $"runs/{Id(id)}/events?after={after}", null, ct);
    [HttpPost("runs/{id}/reply")] public Task<IActionResult> Reply(string id, [FromBody] JsonElement body, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/reply", body, ct);
    [HttpPost("runs/{id}/stop")] public Task<IActionResult> Stop(string id, CancellationToken ct) => Forward(HttpMethod.Post, $"runs/{Id(id)}/stop", null, ct);
}
