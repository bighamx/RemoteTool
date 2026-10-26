using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using RemoteTool.WebApi.Services.PhoneControl;

var folder = Path.Combine(Path.GetTempPath(), "remotetool-phone-tests-" + Guid.NewGuid().ToString("N"));
var gateway = new PhoneGateway(folder);
var builder = WebApplication.CreateBuilder(); builder.WebHost.UseUrls("http://127.0.0.1:0"); builder.Logging.ClearProviders();
builder.Services.AddSingleton(gateway);
builder.Services.AddControllers();
builder.Services.AddAuthentication("test").AddScheme<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions, TestAuth>("test", _ => { });
builder.Services.AddAuthorization();
await using var app = builder.Build(); app.UseWebSockets();
app.UseAuthentication(); app.UseAuthorization(); app.MapControllers();
app.Map("/phone", async context => { using var ws = await context.WebSockets.AcceptWebSocketAsync(); await gateway.Attach(ws, context.RequestAborted); });
await app.StartAsync();
var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single().Replace("http://", "ws://") + "/phone";
static void Check(bool ok, string message) { if (!ok) throw new Exception(message); }
static async Task<JsonObject> Receive(ClientWebSocket socket) {
    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
    var data = new byte[64 * 1024]; var result = await socket.ReceiveAsync(data, timeout.Token);
    return JsonNode.Parse(Encoding.UTF8.GetString(data, 0, result.Count))!.AsObject();
}
using var client = new ClientWebSocket(); await client.ConnectAsync(new Uri(address), default);
var tools = new JsonArray(new JsonObject { ["name"] = "phone_observe", ["annotations"] = new JsonObject { ["destructiveHint"] = false } }, new JsonObject { ["name"] = "phone_shell", ["annotations"] = new JsonObject { ["destructiveHint"] = true } });
foreach (var tool in tools) { tool!["description"] = "Test phone tool"; tool["inputSchema"] = new JsonObject { ["type"] = "object", ["properties"] = new JsonObject() }; }
var hello = new JsonObject { ["type"] = "hello", ["phone_id"] = "phone-test", ["phone_token"] = new string('a', 48), ["device"] = new JsonObject { ["name"] = "Test Phone" }, ["tools"] = tools };
await client.SendAsync(Encoding.UTF8.GetBytes(hello.ToJsonString()), WebSocketMessageType.Text, true, default);
Check((await Receive(client))["type"]!.ToString() == "ready", "handshake");
Check(gateway.Devices().Count == 1, "device registration");
Check(gateway.Tools("phone-test", false).Count == 1, "advanced filtering");
var binding = gateway.CreateBinding("phone-test", false, "hermes", "work");
var id = binding["id"]!.ToString(); var token = binding["token"]!.ToString();
Check(gateway.AuthorizeBinding(id, token) == null, "unbound token rejected");
gateway.BindSession(id, "session-1");
Check(gateway.AuthorizeBinding(id, token, "session-1") != null, "bound token accepted");
Check(gateway.AuthorizeBinding(id, token, "session-2") == null, "cross-session rejected");
Check(gateway.AuthorizeBinding(id, new string('x', 64)) == null, "invalid token rejected");
var invoke = gateway.Invoke("phone-test", "phone_observe", new(), "request-1", false, default);
var command = await Receive(client); Check(command["request_id"]!.ToString() == "request-1", "forward request ID");
await client.SendAsync(Encoding.UTF8.GetBytes(new JsonObject { ["type"] = "result", ["request_id"] = "request-1", ["result"] = new JsonObject { ["isError"] = false, ["content"] = new JsonArray() } }.ToJsonString()), WebSocketMessageType.Text, true, default);
Check((await invoke)["isError"]!.GetValue<bool>() == false, "roundtrip result");
await gateway.AgentSeen("phone-test", "agent-a", "Codex", default);
var presence = await Receive(client);
Check(presence["type"]!.ToString() == "agent_presence" && presence["agent_id"]!.ToString() == "agent-a" && presence["agent_name"]!.ToString() == "Codex", "agent connection reaches phone display");
var scoped = gateway.Invoke("phone-test", "phone_observe", new(), "scoped", false, default, "agent-a");
var scopedCommand = await Receive(client);
Check(scopedCommand["agent_id"]!.ToString() == "agent-a" && scopedCommand["agent_name"]!.ToString() == "Codex", "tool calls retain the binding identity");
await client.SendAsync(Encoding.UTF8.GetBytes(new JsonObject { ["type"] = "result", ["request_id"] = "scoped", ["result"] = new JsonObject { ["isError"] = false } }.ToJsonString()), WebSocketMessageType.Text, true, default);
Check(!(await scoped)["isError"]!.GetValue<bool>(), "scoped tool completes");
Check((await gateway.Invoke("phone-test", "phone_shell", new(), "blocked", false, default))["isError"]!.GetValue<bool>(), "advanced invoke rejected");
using var cancel = new CancellationTokenSource(100);
var lost = gateway.Invoke("phone-test", "phone_observe", new(), "lost", false, cancel.Token); await Receive(client);
Check((await lost)["structuredContent"]!["code"]!.ToString() == "delivery_unknown", "lost receipt never replayed");
gateway.RevokeSession("hermes", "other", "session-1"); Check(gateway.AuthorizeBinding(id, token) != null, "profile isolation");
gateway.RevokeSession("hermes", "work", "session-1"); Check(gateway.AuthorizeBinding(id, token) == null, "delete revokes token");
var webRoot = address.Replace("ws://", "http://").Replace("/phone", "");
using var http = new HttpClient();
var unauthorized = await http.PostAsync(webRoot + "/api/phone/v1/connections", new StringContent("{\"phone_id\":\"phone-test\"}", Encoding.UTF8, "application/json"));
Check(unauthorized.StatusCode == System.Net.HttpStatusCode.Unauthorized, "external connection needs login");
http.DefaultRequestHeaders.Authorization = new("Bearer", "admin-test");
var create = await http.PostAsync(webRoot + "/api/phone/v1/connections", new StringContent("{\"phone_id\":\"phone-test\",\"allow_advanced\":false}", Encoding.UTF8, "application/json"));
create.EnsureSuccessStatusCode();
var external = JsonNode.Parse(await create.Content.ReadAsStringAsync())!;
Check(gateway.ExternalConnections().Count == 1, "external connection created without chat");
var presenceEvents = System.Threading.Channels.Channel.CreateUnbounded<string>();
var responder = Task.Run(async () => {
    try { while (true) { var call = await Receive(client); if (call["type"]?.ToString() == "agent_presence") { presenceEvents.Writer.TryWrite(call["agent_id"]!.ToString()); continue; } await client.SendAsync(Encoding.UTF8.GetBytes(new JsonObject {
        ["type"] = "result", ["request_id"] = call["request_id"]!.ToString(), ["result"] = new JsonObject { ["isError"] = false,
        ["content"] = new JsonArray(new JsonObject { ["type"] = "text", ["text"] = "test phone observed" }) }
    }.ToJsonString()), WebSocketMessageType.Text, true, default); } } catch (Exception) { }
});
using var mcpClient = new HttpClient();
mcpClient.DefaultRequestHeaders.Authorization = new("Bearer", external["token"]!.ToString());
var mcpUrl = external["mcp_url"]!.ToString();
var listResponse = await mcpClient.PostAsync(mcpUrl, new StringContent("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", Encoding.UTF8, "application/json"));
Check(JsonNode.Parse(await listResponse.Content.ReadAsStringAsync())!["result"]!["tools"]!.AsArray().Count == 1, "external MCP tool filtering");
async Task McpProbe(string method, string parameters = "{}") {
    var response = await mcpClient.PostAsync(mcpUrl, new StringContent("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"" + method + "\",\"params\":" + parameters + "}", Encoding.UTF8, "application/json"));
    response.EnsureSuccessStatusCode();
    if (response.Headers.TryGetValues("Mcp-Session-Id", out var sessionHeaders)) {
        mcpClient.DefaultRequestHeaders.Remove("Mcp-Session-Id");
        mcpClient.DefaultRequestHeaders.Add("Mcp-Session-Id", sessionHeaders.Single());
    }
    var envelope = JsonNode.Parse(await response.Content.ReadAsStringAsync());
    Check(envelope?["error"] == null, "MCP probe " + method);
    if (method == "initialize") Check(envelope?["result"]?["instructions"]?.ToString() == PhoneMcpProtocol.Instructions,
        "relay initialize exposes session reuse and recovery instructions");
}
await McpProbe("ping");
// A tool reply is a websocket ordering barrier: all earlier presence frames have been consumed.
await McpProbe("tools/call", "{\"name\":\"phone_observe\",\"arguments\":{}}");
Check(!presenceEvents.Reader.TryRead(out _), "discovery and ping do not reactivate a disconnected agent");
await McpProbe("initialize", "{\"clientInfo\":{\"name\":\"Codex\"}}");
await McpProbe("tools/call", "{\"name\":\"phone_observe\",\"arguments\":{}}");
Check(presenceEvents.Reader.TryRead(out var connectedAgent) && connectedAgent == external["id"] + ":" + mcpClient.DefaultRequestHeaders.GetValues("Mcp-Session-Id").Single(), "initialize creates an isolated client identity");
Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
const string clientName = "编码验证：中文正常";
const string initializeChinese = """{"jsonrpc":"2.0","id":11,"method":"initialize","params":{"clientInfo":{"name":"编码验证：中文正常"}}}""";
foreach (var encodingCase in new[] {
    ("utf8", Encoding.UTF8.GetBytes(initializeChinese)),
    ("gbk", Encoding.GetEncoding(936).GetBytes(initializeChinese)),
    ("double-encoded", Encoding.UTF8.GetBytes(Encoding.Latin1.GetString(Encoding.UTF8.GetBytes(initializeChinese)))),
    ("utf8-bom", new byte[] { 0xEF, 0xBB, 0xBF }.Concat(Encoding.UTF8.GetBytes(initializeChinese)).ToArray())
}) {
    using var content = new ByteArrayContent(encodingCase.Item2);
    content.Headers.ContentType = new("application/json");
    using var response = await mcpClient.PostAsync(mcpUrl, content);
    var sessionId = response.Headers.GetValues("Mcp-Session-Id").Single();
    Check(response.IsSuccessStatusCode && gateway.AgentName(external["id"] + ":" + sessionId) == clientName, "MCP raw request encoding: " + encodingCase.Item1);
}
foreach (var malformed in new[] { "{", "[]", "null", "\"text\"" }) {
    using var response = await mcpClient.PostAsync(mcpUrl, new StringContent(malformed, Encoding.UTF8, "application/json"));
    Check(response.StatusCode == System.Net.HttpStatusCode.BadRequest, "invalid JSON body returns 400");
}
if (Environment.GetEnvironmentVariable("REMOTETOOL_MCP_TEST_PYTHON") is { Length: > 0 } python) {
    var info = new System.Diagnostics.ProcessStartInfo(python) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
    var repo = new DirectoryInfo(AppContext.BaseDirectory);
    while (repo != null && !Directory.Exists(Path.Combine(repo.FullName, "tools", "PhoneTools"))) repo = repo.Parent;
    info.ArgumentList.Add(Path.Combine(repo!.FullName, "tools", "PhoneTools", "tests", "check_mcp_client.py"));
    info.Environment["REMOTETOOL_PHONE_URL"] = mcpUrl; info.Environment["REMOTETOOL_PHONE_TOKEN"] = external["token"]!.ToString();
    using var reference = System.Diagnostics.Process.Start(info)!;
    var output = reference.StandardOutput.ReadToEndAsync(); var errors = reference.StandardError.ReadToEndAsync();
    using var deadline = new CancellationTokenSource(TimeSpan.FromSeconds(25));
    try { await reference.WaitForExitAsync(deadline.Token); } catch { reference.Kill(true); throw; }
    Check(reference.ExitCode == 0, "reference MCP client: " + await errors); Console.WriteLine(await output);
}
foreach (var requested in new[] { "2025-11-25", "2025-06-18", "2025-03-26", "unsupported" }) {
    using var versionResponse = await mcpClient.PostAsync(mcpUrl, new StringContent(new JsonObject {
        ["jsonrpc"]="2.0", ["id"]=20, ["method"]="initialize", ["params"]=new JsonObject { ["protocolVersion"]=requested }
    }.ToJsonString(), Encoding.UTF8, "application/json"));
    Check(JsonNode.Parse(await versionResponse.Content.ReadAsStringAsync())!["result"]!["protocolVersion"]!.ToString() ==
        (requested == "unsupported" ? "2025-11-25" : requested), "relay negotiates version " + requested);
}
foreach (var bad in new[] { "{}", "{\"tool\":7}", "{\"tool\":\"phone_observe\",\"arguments\":[]}",
    "{\"tool\":\"phone_observe\",\"request_id\":{}}", "{\"tool\":\"phone_observe\",\"allow_advanced\":\"yes\"}" }) {
    using var invalid = await mcpClient.PostAsync(mcpUrl[..^4], new StringContent(bad, Encoding.UTF8, "application/json"));
    Check((int)invalid.StatusCode == 400 && JsonNode.Parse(await invalid.Content.ReadAsStringAsync())!["code"]!.ToString() == "invalid_arguments", "legacy bound parameters validated");
    var adminBody = JsonNode.Parse(bad)!.AsObject(); adminBody["phone_id"]="phone-test";
    using var invalidAdmin = await http.PostAsync(webRoot + "/api/phone/v1/invocations", new StringContent(adminBody.ToJsonString(), Encoding.UTF8, "application/json"));
    Check((int)invalidAdmin.StatusCode == 400, "admin invocation parameters validated");
}
foreach (var badRequest in new[] {
    """{"jsonrpc":"2.0","id":21,"method":7}""",
    """{"jsonrpc":"2.0","id":21,"method":"initialize","params":{"protocolVersion":7}}""",
    """{"jsonrpc":"2.0","id":21,"method":"tools/call","params":{"name":"phone_observe","_meta":[]}}"""
}) {
    using var invalidMcp = await mcpClient.PostAsync(mcpUrl, new StringContent(badRequest, Encoding.UTF8, "application/json"));
    Check(JsonNode.Parse(await invalidMcp.Content.ReadAsStringAsync())!["error"]!["code"]!.GetValue<int>() is -32600 or -32602, "MCP invalid envelopes and metadata validated");
}
var concurrentCalls = Enumerable.Range(0, 20).Select(async index => {
    using var response = await mcpClient.PostAsync(mcpUrl, new StringContent(new JsonObject {
        ["jsonrpc"]="2.0", ["id"]=100+index, ["method"]="tools/call", ["params"]=new JsonObject {
            ["name"]="phone_observe", ["arguments"]=new JsonObject(), ["_meta"]=new JsonObject { ["remotetool/request_id"]="parallel-"+index }
        }
    }.ToJsonString(), Encoding.UTF8, "application/json"));
    var reply = JsonNode.Parse(await response.Content.ReadAsStringAsync())!;
    Check(reply["id"]!.GetValue<int>() == 100+index && reply["result"]!["isError"]!.GetValue<bool>() == false, "20 concurrent MCP calls keep response identity");
});
await Task.WhenAll(concurrentCalls);
var otherHost = new PhoneGateway(folder);
Check(otherHost.AuthorizeBinding(external["id"]!.ToString(), external["token"]!.ToString()) != null, "second host loads binding cache");
var revoked = await http.DeleteAsync(webRoot + "/api/phone/v1/bindings/" + external["id"]); revoked.EnsureSuccessStatusCode();
Check(otherHost.AuthorizeBinding(external["id"]!.ToString(), external["token"]!.ToString()) == null, "cached authorization notices cross-host revocation");
var afterRevoke = await mcpClient.PostAsync(mcpUrl, new StringContent("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}", Encoding.UTF8, "application/json"));
Check(afterRevoke.StatusCode == System.Net.HttpStatusCode.Unauthorized, "external MCP revoked");
var offlineBinding = gateway.CreateBinding("phone-test", false, "external", "default");
gateway.BindSession(offlineBinding["id"]!.ToString(), "offline-test");
client.Abort();
await responder;
for (var i=0; i<100 && gateway.Devices().Count>0; i++) await Task.Delay(10);
using var offlineClient = new HttpClient(); offlineClient.DefaultRequestHeaders.Authorization = new("Bearer", offlineBinding["token"]!.ToString());
var offlineUrl = webRoot + "/api/phone/v1/bound/" + offlineBinding["id"] + "/mcp";
var offlineList = await offlineClient.PostAsync(offlineUrl, new StringContent("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\"}", Encoding.UTF8, "application/json"));
var offlineText = await offlineList.Content.ReadAsStringAsync();
var offlineError = JsonNode.Parse(offlineText)!;
Check(offlineList.IsSuccessStatusCode && offlineError["error"]!["code"]!.GetValue<int>() == -32000 && offlineError["error"]!["data"]!["code"]!.ToString() == "phone_offline", "offline tools/list uses JSON-RPC error");
Check(!offlineText.Contains("Exception") && !offlineText.Contains("RemoteTool\\") && !offlineText.Contains(" at "), "offline response leaks no stack or paths");
var offlineCall = await offlineClient.PostAsync(offlineUrl, new StringContent("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\",\"params\":{\"name\":\"phone_observe\",\"arguments\":{}}}", Encoding.UTF8, "application/json"));
Check(JsonNode.Parse(await offlineCall.Content.ReadAsStringAsync())!["result"]!["isError"]!.GetValue<bool>(), "offline tools/call keeps tool-result contract");
var badParams = await offlineClient.PostAsync(offlineUrl, new StringContent("{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"tools/call\"}", Encoding.UTF8, "application/json"));
Check(JsonNode.Parse(await badParams.Content.ReadAsStringAsync())!["error"]!["code"]!.GetValue<int>() == -32602, "invalid tool params use JSON-RPC error");
await app.StopAsync();
if (Path.GetFullPath(folder).StartsWith(Path.GetFullPath(Path.Combine(Path.GetTempPath(), "remotetool-phone-tests-")), StringComparison.OrdinalIgnoreCase)) Directory.Delete(folder, true);
Console.WriteLine("Phone gateway checks passed: WS roundtrip, isolation, unknown receipt, external connection API, MCP and revocation.");

sealed class TestAuth(Microsoft.Extensions.Options.IOptionsMonitor<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions> options, ILoggerFactory logger, System.Text.Encodings.Web.UrlEncoder encoder)
    : Microsoft.AspNetCore.Authentication.AuthenticationHandler<Microsoft.AspNetCore.Authentication.AuthenticationSchemeOptions>(options, logger, encoder) {
    protected override Task<Microsoft.AspNetCore.Authentication.AuthenticateResult> HandleAuthenticateAsync() {
        if (Request.Headers.Authorization != "Bearer admin-test") return Task.FromResult(Microsoft.AspNetCore.Authentication.AuthenticateResult.NoResult());
        var principal = new System.Security.Claims.ClaimsPrincipal(new System.Security.Claims.ClaimsIdentity(new[] { new System.Security.Claims.Claim("name", "test-admin") }, "test"));
        return Task.FromResult(Microsoft.AspNetCore.Authentication.AuthenticateResult.Success(new Microsoft.AspNetCore.Authentication.AuthenticationTicket(principal, "test")));
    }
}
