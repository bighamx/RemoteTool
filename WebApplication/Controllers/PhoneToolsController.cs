using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services.PhoneControl;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Controllers;

[ApiController, Route("api/phone/v1")]
public sealed class PhoneToolsController(PhoneGateway gateway, ILogger<PhoneToolsController> logger) : ControllerBase {
    [Authorize, HttpGet("devices")] public object Devices() => new JsonObject { ["devices"] = gateway.Devices() };
    [Authorize, HttpGet("connections")] public object Connections() => new JsonObject { ["connections"] = gateway.ExternalConnections() };
    [Authorize, HttpPost("connections")] public IActionResult CreateConnection([FromBody] JsonObject body) {
        if (!TryText(body, "phone_id", out var phone) || string.IsNullOrWhiteSpace(phone) || !ValidAdvanced(body))
            return InvalidArguments("请选择手机并提供有效的 allow_advanced 布尔值");
        try {
            var binding = gateway.CreateBinding(phone, body["allow_advanced"]?.GetValue<bool>() == true, "external", "default");
            var id = binding["id"]!.ToString(); gateway.BindSession(id, "external-" + id);
            return Ok(new JsonObject { ["id"] = id, ["phone_id"] = phone, ["token"] = binding["token"]!.ToString(),
                ["mcp_url"] = $"{Request.Scheme}://{Request.Host}{Request.PathBase}/api/phone/v1/bound/{id}/mcp" });
        } catch (PhoneOfflineException) { return Conflict(new { message = "手机尚未连接当前 RemoteTool 通道", code = "phone_offline" }); }
    }
    [Authorize, HttpGet("devices/{phone}/tools")] public IActionResult Tools(string phone, [FromQuery] bool advanced = false) {
        try { return Ok(new JsonObject { ["tools"] = gateway.Tools(phone, advanced) }); }
        catch (PhoneOfflineException) { return Conflict(new { code = "phone_offline", message = "手机已离线" }); }
    }
    [Authorize, HttpPost("invocations")] public async Task<IActionResult> Invoke([FromBody] JsonObject body, CancellationToken ct) {
        if (!ValidInvocation(body, true) || !TryText(body, "phone_id", out var phone) || string.IsNullOrWhiteSpace(phone))
            return InvalidArguments("phone_id、tool、request_id 必须为非空字符串，arguments 必须为对象");
        return Ok(await gateway.Invoke(phone, body["tool"]!.GetValue<string>(), body["arguments"] as JsonObject ?? new(), body["request_id"]!.GetValue<string>(), body["allow_advanced"]?.GetValue<bool>() == true, ct));
    }
    [Authorize, HttpDelete("bindings/{id}")] public async Task<IActionResult> Revoke(string id, CancellationToken ct) { await gateway.RevokeBinding(id, ct); return Ok(new { revoked = true }); }
    [HttpPost("bound/{id}")] public async Task<IActionResult> Bound(string id, [FromBody] JsonObject body, CancellationToken ct) {
        var token = Request.Headers.Authorization.ToString().Replace("Bearer ", "", StringComparison.Ordinal);
        var row = gateway.AuthorizeBinding(id, token, Request.Headers["X-Phone-Session"].FirstOrDefault()); if (row == null) return Unauthorized();
        var phone = row["phone_id"]!.ToString(); var advanced = row["advanced"]?.GetValue<bool>() == true;
        if (body["action"]?.ToString() == "list") {
            try { return Ok(new JsonObject { ["tools"] = gateway.Tools(phone, advanced) }); }
            catch (PhoneOfflineException) { return Ok(PhoneGateway.Error("phone_offline", "手机已离线")); }
        }
        if (!ValidInvocation(body, false)) return InvalidArguments("tool 必须为非空字符串，arguments 必须为对象，request_id 必须为有效字符串");
        return Ok(await gateway.Invoke(phone, body["tool"]!.GetValue<string>(), body["arguments"] as JsonObject ?? new(), body["request_id"]?.GetValue<string>() ?? Guid.NewGuid().ToString("N"), advanced, ct, id, gateway.AgentName(id, row["agent"]?.ToString() ?? "Agent")));
    }
    [HttpPost("bound/{id}/mcp")]
    public async Task<IActionResult> Mcp(string id, CancellationToken ct) {
        // 自行读取请求体：客户端若按 GBK 发送或发生双重编码，ASP.NET 的 UTF-8 绑定
        // 会静默变成替换字符，这里统一修复后再进入 JSON-RPC 处理。
        var body = await Services.TextEncodingRepair.ReadJsonObjectAsync(Request, ct);
        if (body == null) return BadRequest(new { error = "invalid_request", detail = "Body must be a JSON object" });
        try { return await McpCore(id, body, ct); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (PhoneOfflineException) {
            return McpError(body, -32000, "Phone is offline", "phone_offline");
        }
        catch (PhoneMcpSessionException error) { return McpError(body, -32000, error.Message, error.Code); }
        catch (Exception error) {
            logger.LogError(error, "Phone MCP request failed");
            return McpError(body, -32603, "Internal error", "internal_error");
        }
    }
    private IActionResult McpError(JsonObject body, int code, string message, string detail) => Measured(new JsonObject {
        ["jsonrpc"] = "2.0", ["id"] = body["id"]?.DeepClone(),
        ["error"] = new JsonObject { ["code"] = code, ["message"] = message, ["data"] = new JsonObject { ["code"] = detail } }
    });
    private async Task<IActionResult> McpCore(string id, JsonObject body, CancellationToken ct) {
        if (Request.Headers.ContainsKey("Origin")) return StatusCode(403);
        var row = gateway.AuthorizeBinding(id, Request.Headers.Authorization.ToString().Replace("Bearer ", "", StringComparison.Ordinal)); if (row == null) return Unauthorized();
        if (!body.ContainsKey("id")) return Accepted();
        if (body["jsonrpc"]?.ToString() != "2.0" || !TryText(body, "method", out var method) || string.IsNullOrWhiteSpace(method))
            return McpError(body, -32600, "Invalid Request", "invalid_request");
        if (body["params"] != null && body["params"] is not JsonObject)
            return McpError(body, -32602, "Invalid params", "invalid_params");
        JsonNode result;
        var p = body["params"] as JsonObject ?? new();
        if (method == "initialize" && (p["protocolVersion"] != null && !TryText(p, "protocolVersion", out _) ||
            p["clientInfo"] != null && p["clientInfo"] is not JsonObject ||
            p["clientInfo"] is JsonObject info && info["name"] != null && !TryText(info, "name", out _)))
            return McpError(body, -32602, "Invalid initialize params", "invalid_params");
        PhoneMcpSessions.Client? client = null;
        if (method == "initialize") {
            if (p["_meta"] != null && p["_meta"] is not JsonObject || p["_meta"] is JsonObject initializeMeta && initializeMeta["remotetool/resume_token"] != null && !TryText(initializeMeta, "remotetool/resume_token", out _))
                return McpError(body, -32602, "Invalid recovery metadata", "invalid_params");
            client = gateway.McpSessions.Create(id, (p["clientInfo"] as JsonObject)?["name"]?.ToString() ?? "Agent", p["_meta"]?["remotetool/resume_token"]?.GetValue<string>());
            Response.Headers["Mcp-Session-Id"] = client.Id;
            await gateway.AgentSeen(row["phone_id"]!.ToString(), client.AgentId, client.Name, ct, client.Generation);
        } else if (Request.Headers["Mcp-Session-Id"].FirstOrDefault() is { Length: > 0 } sessionId) {
            client = gateway.McpSessions.Get(id, sessionId);
            if (client == null) return NotFound(new { code="session_not_found", message="Initialize a new MCP session; do not replay uncertain actions" });
        }
        switch (method) {
            case "initialize": result = new JsonObject { ["protocolVersion"] = PhoneMcpProtocol.SelectVersion(p["protocolVersion"]?.GetValue<string>()), ["capabilities"] = new JsonObject { ["tools"] = new JsonObject() }, ["serverInfo"] = new JsonObject { ["name"] = "remotetool-phone-relay", ["version"] = "1.2.0" },
                ["instructions"] = PhoneMcpProtocol.Instructions,
                ["_meta"] = new JsonObject { ["remotetool"] = new JsonObject { ["resume_token"] = client!.ResumeToken, ["agent_id"] = client.LogicalId, ["generation"] = client.Generation, ["self_cleanup"] = "resume-token", ["idle_timeout_seconds"] = 300 } } }; break;
            case "ping": result = new JsonObject(); break;
            case "tools/list": result = new JsonObject { ["tools"] = gateway.Tools(row["phone_id"]!.ToString(), row["advanced"]?.GetValue<bool>() == true) }; break;
            case "tools/call":
                if (p["name"] is not JsonValue nameValue || !nameValue.TryGetValue<string>(out var toolName) || string.IsNullOrWhiteSpace(toolName) || p["arguments"] != null && p["arguments"] is not JsonObject)
                    return McpError(body, -32602, "Invalid params", "invalid_params");
                if (p["_meta"] != null && p["_meta"] is not JsonObject ||
                    p["_meta"] is JsonObject meta && meta["remotetool/request_id"] != null && !TryText(meta, "remotetool/request_id", out _))
                    return McpError(body, -32602, "Invalid request metadata", "invalid_params");
                using (client == null ? null : gateway.McpSessions.BeginCall(client))
                    result = await gateway.Invoke(row["phone_id"]!.ToString(), toolName, p["arguments"] as JsonObject ?? new(), p["_meta"]?["remotetool/request_id"]?.ToString() ?? "mcp-" + Guid.NewGuid().ToString("N"), row["advanced"]?.GetValue<bool>() == true, ct, client?.AgentId ?? id, client?.Name ?? "Agent"); break;
            default: return Ok(new JsonObject { ["jsonrpc"] = "2.0", ["id"] = body["id"]?.DeepClone(), ["error"] = new JsonObject { ["code"] = -32601, ["message"] = "Method not found" } });
        }
        if (client != null && method != "initialize" && method != "tools/call")
            await gateway.TouchAgent(row["phone_id"]!.ToString(), client.AgentId, ct, client.Generation);
        return Measured(new JsonObject { ["jsonrpc"] = "2.0", ["id"] = body["id"]?.DeepClone(), ["result"] = result });
    }
    private IActionResult Measured(JsonObject data) {
        var started = System.Diagnostics.Stopwatch.GetTimestamp();
        var body = data.ToJsonString(new System.Text.Json.JsonSerializerOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping });
        Response.Headers["X-RemoteTool-Serialize-Ms"] = System.Diagnostics.Stopwatch.GetElapsedTime(started).TotalMilliseconds.ToString(System.Globalization.CultureInfo.InvariantCulture);
        Response.Headers.CacheControl = "no-store";
        Response.Headers["X-RemoteTool-Response-Bytes"] = System.Text.Encoding.UTF8.GetByteCount(body).ToString(System.Globalization.CultureInfo.InvariantCulture);
        return Content(body, "application/json; charset=utf-8");
    }
    private static bool TryText(JsonObject body, string key, out string? value) {
        value = null;
        return body[key] is JsonValue field && field.TryGetValue(out value);
    }
    private static bool ValidAdvanced(JsonObject body) => body["allow_advanced"] == null ||
        body["allow_advanced"] is JsonValue value && value.TryGetValue<bool>(out _);
    private static bool ValidInvocation(JsonObject body, bool requireId) =>
        TryText(body, "tool", out var tool) && !string.IsNullOrWhiteSpace(tool) &&
        (body["arguments"] == null || body["arguments"] is JsonObject) && ValidAdvanced(body) &&
        (!requireId && !body.ContainsKey("request_id") || TryText(body, "request_id", out var id) &&
            id != null && System.Text.RegularExpressions.Regex.IsMatch(id, "^[a-zA-Z0-9_.:-]{1,160}$"));
    private IActionResult InvalidArguments(string message) => BadRequest(new { code = "invalid_arguments", message });
    [HttpDelete("bound/{id}/mcp")] public async Task<IActionResult> DeleteMcp(string id, CancellationToken ct) {
        if (Request.Headers.ContainsKey("Origin")) return StatusCode(403);
        var row = gateway.AuthorizeBinding(id, Request.Headers.Authorization.ToString().Replace("Bearer ", "", StringComparison.Ordinal));
        if (row == null) return Unauthorized();
        var resume = Request.Headers["X-RemoteTool-Resume-Token"].ToString();
        var session = Request.Headers["Mcp-Session-Id"].ToString();
        if (!string.IsNullOrEmpty(resume) && !string.IsNullOrEmpty(session)) return BadRequest(new { code="invalid_arguments", message="Specify session ID or resume token, not both" });
        if (string.IsNullOrEmpty(resume) && string.IsNullOrEmpty(session)) return BadRequest(new { code="session_required", message="Deleting a whole binding without owner credentials is not supported" });
        PhoneMcpSessions.Client? client;
        try { client = !string.IsNullOrEmpty(resume) ? gateway.McpSessions.RemoveByResumeToken(id, resume) : gateway.McpSessions.Remove(id, session); }
        catch (PhoneMcpSessionException error) { return Conflict(new { code=error.Code, execution="not_started", retry_after_ms=250 }); }
        if (client == null) return NotFound(new { code=string.IsNullOrEmpty(resume) ? "session_not_found" : "resume_unknown" });
        await gateway.DisconnectAgent(row["phone_id"]!.ToString(), client.AgentId, ct, client.Generation);
        return NoContent();
    }
    [HttpGet("bound/{id}/mcp")] public IActionResult NoStream() => StatusCode(405);
}
