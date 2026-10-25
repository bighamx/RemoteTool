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
        var phone = body["phone_id"]?.ToString();
        if (string.IsNullOrWhiteSpace(phone)) return BadRequest(new { message = "请选择手机", code = "invalid_arguments" });
        try {
            var binding = gateway.CreateBinding(phone, body["allow_advanced"]?.GetValue<bool>() == true, "external", "default");
            var id = binding["id"]!.ToString(); gateway.BindSession(id, "external-" + id);
            return Ok(new JsonObject { ["id"] = id, ["phone_id"] = phone, ["token"] = binding["token"]!.ToString(),
                ["mcp_url"] = $"{Request.Scheme}://{Request.Host}{Request.PathBase}/api/phone/v1/bound/{id}/mcp" });
        } catch (InvalidOperationException) { return Conflict(new { message = "手机尚未连接当前 RemoteTool 通道", code = "phone_offline" }); }
    }
    [Authorize, HttpGet("devices/{phone}/tools")] public IActionResult Tools(string phone, [FromQuery] bool advanced = false) {
        try { return Ok(new JsonObject { ["tools"] = gateway.Tools(phone, advanced) }); }
        catch (InvalidOperationException error) when (error.Message == "phone_offline") { return Conflict(new { code = "phone_offline", message = "手机已离线" }); }
    }
    [Authorize, HttpPost("invocations")] public Task<JsonObject> Invoke([FromBody] JsonObject body, CancellationToken ct) => gateway.Invoke(body["phone_id"]!.ToString(), body["tool"]!.ToString(), body["arguments"] as JsonObject ?? new(), body["request_id"]!.ToString(), body["allow_advanced"]?.GetValue<bool>() == true, ct);
    [Authorize, HttpDelete("bindings/{id}")] public async Task<IActionResult> Revoke(string id, CancellationToken ct) { await gateway.RevokeBinding(id, ct); return Ok(new { revoked = true }); }
    [HttpPost("bound/{id}")] public async Task<IActionResult> Bound(string id, [FromBody] JsonObject body, CancellationToken ct) {
        var token = Request.Headers.Authorization.ToString().Replace("Bearer ", "", StringComparison.Ordinal);
        var row = gateway.AuthorizeBinding(id, token, Request.Headers["X-Phone-Session"].FirstOrDefault()); if (row == null) return Unauthorized();
        var phone = row["phone_id"]!.ToString(); var advanced = row["advanced"]?.GetValue<bool>() == true;
        if (body["action"]?.ToString() == "list") {
            try { return Ok(new JsonObject { ["tools"] = gateway.Tools(phone, advanced) }); }
            catch (InvalidOperationException error) when (error.Message == "phone_offline") { return Ok(PhoneGateway.Error("phone_offline", "手机已离线")); }
        }
        return Ok(await gateway.Invoke(phone, body["tool"]!.ToString(), body["arguments"] as JsonObject ?? new(), body["request_id"]?.ToString() ?? Guid.NewGuid().ToString("N"), advanced, ct, id, gateway.AgentName(id, row["agent"]?.ToString() ?? "Agent")));
    }
    [HttpPost("bound/{id}/mcp")]
    public async Task<IActionResult> Mcp(string id, CancellationToken ct) {
        // 自行读取请求体：客户端若按 GBK 发送或发生双重编码，ASP.NET 的 UTF-8 绑定
        // 会静默变成替换字符，这里统一修复后再进入 JSON-RPC 处理。
        var body = await Services.TextEncodingRepair.ReadJsonObjectAsync(Request, ct);
        if (body == null) return BadRequest(new { error = "invalid_request", detail = "Body must be a JSON object" });
        try { return await McpCore(id, body, ct); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (InvalidOperationException error) when (error.Message == "phone_offline") {
            return McpError(body, -32000, "Phone is offline", "phone_offline");
        }
        catch (Exception error) {
            logger.LogError(error, "Phone MCP request failed");
            return McpError(body, -32603, "Internal error", "internal_error");
        }
    }
    private IActionResult McpError(JsonObject body, int code, string message, string detail) => Ok(new JsonObject {
        ["jsonrpc"] = "2.0", ["id"] = body["id"]?.DeepClone(),
        ["error"] = new JsonObject { ["code"] = code, ["message"] = message, ["data"] = new JsonObject { ["code"] = detail } }
    });
    private async Task<IActionResult> McpCore(string id, JsonObject body, CancellationToken ct) {
        if (Request.Headers.ContainsKey("Origin")) return StatusCode(403);
        var row = gateway.AuthorizeBinding(id, Request.Headers.Authorization.ToString().Replace("Bearer ", "", StringComparison.Ordinal)); if (row == null) return Unauthorized();
        if (!body.ContainsKey("id")) return Accepted();
        if (body["jsonrpc"]?.ToString() != "2.0" || body["method"] is not JsonValue)
            return McpError(body, -32600, "Invalid Request", "invalid_request");
        if (body["params"] != null && body["params"] is not JsonObject)
            return McpError(body, -32602, "Invalid params", "invalid_params");
        JsonNode result;
        var p = body["params"] as JsonObject ?? new();
        if (body["method"]?.ToString() == "initialize")
            await gateway.AgentSeen(row["phone_id"]!.ToString(), id, (p["clientInfo"] as JsonObject)?["name"]?.ToString() ?? "Agent", ct);
        switch (body["method"]?.ToString()) {
            case "initialize": result = new JsonObject { ["protocolVersion"] = "2025-11-25", ["capabilities"] = new JsonObject { ["tools"] = new JsonObject() }, ["serverInfo"] = new JsonObject { ["name"] = "remotetool-phone-relay", ["version"] = "1.1.0" },
                ["instructions"] = "Use phone_begin_ui before a UI batch, phone_end_ui when finished, and phone_notify_user(kind=help) plus phone_get_user_reply for user assistance. Call phone_disconnect as your final phone tool when done to dismiss your indicator and pending notices. Observe before acting and verify afterward. Never automatically retry delivery_unknown." }; break;
            case "ping": result = new JsonObject(); break;
            case "tools/list": result = new JsonObject { ["tools"] = gateway.Tools(row["phone_id"]!.ToString(), row["advanced"]?.GetValue<bool>() == true) }; break;
            case "tools/call":
                if (p["name"] is not JsonValue nameValue || !nameValue.TryGetValue<string>(out var toolName) || string.IsNullOrWhiteSpace(toolName) || p["arguments"] != null && p["arguments"] is not JsonObject)
                    return McpError(body, -32602, "Invalid params", "invalid_params");
                result = await gateway.Invoke(row["phone_id"]!.ToString(), toolName, p["arguments"] as JsonObject ?? new(), p["_meta"]?["remotetool/request_id"]?.ToString() ?? "mcp-" + Guid.NewGuid().ToString("N"), row["advanced"]?.GetValue<bool>() == true, ct, id, gateway.AgentName(id)); break;
            default: return Ok(new JsonObject { ["jsonrpc"] = "2.0", ["id"] = body["id"]?.DeepClone(), ["error"] = new JsonObject { ["code"] = -32601, ["message"] = "Method not found" } });
        }
        return Ok(new JsonObject { ["jsonrpc"] = "2.0", ["id"] = body["id"]?.DeepClone(), ["result"] = result });
    }
    [HttpGet("bound/{id}/mcp")] public IActionResult NoStream() => StatusCode(405);
}
