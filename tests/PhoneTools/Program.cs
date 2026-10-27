using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using RemoteTool.WebApi.Services.PhoneControl;

var folder = Path.Combine(Path.GetTempPath(), "remotetool-phone-tests-" + Guid.NewGuid().ToString("N"));
var gateway = new PhoneGateway(folder);
var integration = new PhoneSessionIntegration(gateway, new ConfigurationBuilder().Build(),
    Microsoft.Extensions.Logging.Abstractions.NullLogger<PhoneSessionIntegration>.Instance);
foreach (var agent in new[] { "codex", "hermes" }) {
    var native = new JsonObject { ["system_prompt"] = "existing prompt", ["phone_binding"] = new JsonObject(),
        ["phone_description"] = "untrusted", ["phone_tools"] = new JsonObject { ["mode"] = "description", ["phone_id"] = "phone-offline" } };
    Check(await integration.Prepare(native, agent, new DefaultHttpContext().Request, default) == null,
        agent + " offline description must permit ordinary session creation");
    Check(!native.ContainsKey("phone_tools") && !native.ContainsKey("phone_binding") && !native.ContainsKey("phone_description") &&
        native["system_prompt"]!.ToString() == "existing prompt", agent + " offline fallback must strip injection fields and preserve original instructions");
    Check(!File.Exists(Path.Combine(folder,"bindings.json")), "offline description must not persist a fake binding");
    var toolsRequest = new JsonObject { ["phone_tools"] = new JsonObject { ["mode"] = "tools", ["phone_id"] = "phone-offline" } };
    var rejected = false;
    try { await integration.Prepare(toolsRequest, agent, new DefaultHttpContext().Request, default); }
    catch (PhoneOfflineException) { rejected = true; }
    Check(rejected, agent + " explicit tool mode must not silently lose requested tools");
}
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
var disconnectBudget = TimeSpan.FromSeconds(1);
long idleClock = 0;
var idleSessions = new PhoneMcpSessions(() => idleClock);
var idleOwner = idleSessions.Create("binding", "idle");
idleClock = 299_999;
Check(idleSessions.DrainExpired().Length == 0, "idle owner survives just before five minutes");
idleClock = 300_000;
Check(idleSessions.DrainExpired().Single().AgentId == idleOwner.AgentId, "five minute expiry emits proactive cleanup");
var idleResumed = idleSessions.Create("binding", "resumed", idleOwner.ResumeToken);
Check(idleResumed.AgentId == idleOwner.AgentId && idleResumed.Generation == 2, "five minute expiry preserves recoverable owner");
using (idleSessions.BeginCall(idleResumed)) {
    idleClock += 600_000;
    Check(idleSessions.DrainExpired().Length == 0, "idle sweep does not interrupt running calls");
}
idleClock += 299_999;
Check(idleSessions.DrainExpired().Length == 0, "idle clock starts after operation completion");
idleClock++;
Check(idleSessions.DrainExpired().Length == 1, "completed owner idles out after five minutes");
var recovery = new PhoneMcpSessions();
var oldTransport = recovery.Create("binding-a", "Agent");
using (recovery.BeginCall(oldTransport)) {
    try { recovery.Create("binding-a", "Agent", oldTransport.ResumeToken); throw new Exception("Active request was taken over"); }
    catch (PhoneMcpSessionException error) { Check(error.Code == "device_busy", "active recovery error"); }
}
try { recovery.Create("binding-b", "Agent", oldTransport.ResumeToken); throw new Exception("Cross-binding recovery accepted"); }
catch (PhoneMcpSessionException error) { Check(error.Code == "resume_unknown", "recovery must enforce binding realm"); }
var recovered = recovery.Create("binding-a", "New display name", oldTransport.ResumeToken);
Check(recovery.Remove("binding-a", oldTransport.Id) == null && recovery.Get("binding-a", recovered.Id) != null, "stale transport DELETE cannot close resumed owner");
Check(recovery.RemoveByResumeToken("binding-b", oldTransport.ResumeToken) == null, "resume cleanup must enforce binding realm");
Check(recovered.Name == "New display name", "recovery accepts the newly declared display name without changing owner");
Check(recovered.Id != oldTransport.Id && recovered.AgentId == oldTransport.AgentId && recovered.Generation == 2, "stable identity across transport recovery");
Check(recovery.Get("binding-a", oldTransport.Id) == null, "old transport fenced");
using (recovery.BeginCall(recovered)) {
    try { recovery.RemoveByResumeToken("binding-a", recovered.ResumeToken); throw new Exception("Running owner deleted"); }
    catch (PhoneMcpSessionException error) { Check(error.Code == "device_busy", "cleanup must not interrupt active operation"); }
}
try { using var stale = recovery.BeginCall(oldTransport); throw new Exception("Stale transport executed"); }
catch (PhoneMcpSessionException error) { Check(error.Code == "session_replaced", "old cached client object fenced"); }
Check(recovery.RemoveByResumeToken("binding-a", recovered.ResumeToken)?.AgentId == recovered.AgentId, "self cleanup removes only the credential owner");
try { recovery.Create("binding-a", "Agent", recovered.ResumeToken); throw new Exception("Revoked recovery accepted"); }
catch (PhoneMcpSessionException error) { Check(error.Code == "resume_unknown", "delete revokes recovery credential"); }
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
foreach (var agent in new[] { "codex", "hermes" }) {
    var native = new JsonObject { ["system_prompt"] = "existing prompt",
        ["phone_tools"] = new JsonObject { ["mode"] = "description", ["phone_id"] = "phone-test" } };
    var descriptionBinding = await integration.Prepare(native,agent,new DefaultHttpContext().Request,default);
    Check(descriptionBinding != null && !native.ContainsKey("phone_tools"), agent + " online description stays available");
    var text = agent == "codex" ? native["phone_description"]!.ToString() : native["system_prompt"]!.ToString();
    Check(text.Contains("当前仅注入手机能力说明"), agent + " description must not claim callable phone tools");
    integration.Abort(descriptionBinding);
}
var binding = gateway.CreateBinding("phone-test", false, "hermes", "work");
var id = binding["id"]!.ToString(); var token = binding["token"]!.ToString();
Check(gateway.AuthorizeBinding(id, token) == null, "unbound token rejected");
gateway.BindSession(id, "session-1");
Check(gateway.AuthorizeBinding(id, token, "session-1") != null, "bound token accepted");
Check(gateway.AuthorizeBinding(id, token, "session-2") == null, "cross-session rejected");
Check(gateway.AuthorizeBinding(id, new string('x', 64)) == null, "invalid token rejected");
var invoke = gateway.Invoke("phone-test", "phone_observe", new(), "request-1", false, default);
var command = await Receive(client); Check(command["request_id"]!.ToString() == "request-1", "forward request ID");
var duplicatePending = (await gateway.Invoke("phone-test", "phone_observe", new(), "request-1", false, default))["structuredContent"]!;
Check(duplicatePending["delivery_state"]!.ToString() == "not_sent" && duplicatePending["execution"]!.ToString() == "unknown" && !duplicatePending["retryable"]!.GetValue<bool>(), "duplicate attempt is not resent but original logical operation can already be running");
await client.SendAsync(Encoding.UTF8.GetBytes(new JsonObject { ["type"] = "result", ["request_id"] = "request-1", ["result"] = new JsonObject { ["isError"] = false, ["content"] = new JsonArray() } }.ToJsonString()), WebSocketMessageType.Text, true, default);
var roundtrip = await invoke;
Check(roundtrip["isError"]!.GetValue<bool>() == false, "roundtrip result");
Check(roundtrip["structuredContent"]!["delivery_state"]!.ToString() == "sent", "successful relay receipt explicitly reports sent");
await gateway.AgentSeen("phone-test", "agent-a", "Codex", default);
var presence = await Receive(client);
Check(presence["type"]!.ToString() == "agent_presence" && presence["agent_id"]!.ToString() == "agent-a" && presence["agent_name"]!.ToString() == "Codex", "agent connection reaches phone display");
var scoped = gateway.Invoke("phone-test", "phone_observe", new(), "scoped", false, default, "agent-a");
var scopedCommand = await Receive(client);
Check(scopedCommand["agent_id"]!.ToString() == "agent-a" && scopedCommand["agent_name"]!.ToString() == "Codex", "tool calls retain the binding identity");
await client.SendAsync(Encoding.UTF8.GetBytes(new JsonObject { ["type"] = "result", ["request_id"] = "scoped", ["result"] = new JsonObject { ["isError"] = false } }.ToJsonString()), WebSocketMessageType.Text, true, default);
Check(!(await scoped)["isError"]!.GetValue<bool>(), "scoped tool completes");
var phoneRejectionTask = gateway.Invoke("phone-test", "phone_observe", new(), "phone-rejection", false, default);
await Receive(client);
await client.SendAsync(Encoding.UTF8.GetBytes(new JsonObject { ["type"] = "result", ["request_id"] = "phone-rejection",
    ["result"] = PhoneGateway.Error("permission_required", "mock denial") }.ToJsonString()), WebSocketMessageType.Text, true, default);
var rejection = await phoneRejectionTask;
Check(rejection["isError"]!.GetValue<bool>() && rejection["structuredContent"]!["delivery_state"]!.ToString() == "sent", "phone-side rejection still proves transport delivery");
Check(JsonNode.Parse(rejection["content"]![0]!["text"]!.ToString())!["delivery_state"]!.ToString() == "sent", "text mirrors structured delivery state");
Check((await gateway.Invoke("phone-test", "phone_shell", new(), "blocked", false, default))["isError"]!.GetValue<bool>(), "advanced invoke rejected");
using var cancel = new CancellationTokenSource(100);
var lost = gateway.Invoke("phone-test", "phone_observe", new(), "lost", false, cancel.Token); await Receive(client);
Check((await lost)["structuredContent"]!["code"]!.ToString() == "delivery_unknown", "lost receipt never replayed");
using (var alreadyCancelled = new CancellationTokenSource()) {
    alreadyCancelled.Cancel();
    var unsent = (await gateway.Invoke("phone-test", "phone_observe", new(), "unsent", false, alreadyCancelled.Token))["structuredContent"]!;
    Check(unsent["code"]!.ToString() == "channel_closed" && unsent["execution"]!.ToString() == "not_started" && unsent["delivery_state"]!.ToString() == "not_sent", "cancel before send is definitely not started");
}
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
async Task<JsonNode> McpProbe(string method, string parameters = "{}") {
    var response = await mcpClient.PostAsync(mcpUrl, new StringContent("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"" + method + "\",\"params\":" + parameters + "}", Encoding.UTF8, "application/json"));
    response.EnsureSuccessStatusCode();
    if (response.Headers.TryGetValues("Mcp-Session-Id", out var sessionHeaders)) {
        mcpClient.DefaultRequestHeaders.Remove("Mcp-Session-Id");
        mcpClient.DefaultRequestHeaders.Add("Mcp-Session-Id", sessionHeaders.Single());
    }
    var responseText = await response.Content.ReadAsStringAsync();
    var envelope = JsonNode.Parse(responseText);
    Check(response.Headers.GetValues("X-RemoteTool-Response-Bytes").Single() == Encoding.UTF8.GetByteCount(responseText).ToString(), "response size metric matches exact JSON bytes");
    Check(envelope?["error"] == null, "MCP probe " + method);
    if (method == "initialize") Check(envelope?["result"]?["instructions"]?.ToString() == PhoneMcpProtocol.Instructions,
        "relay initialize exposes session reuse and recovery instructions");
    return envelope!;
}
await McpProbe("ping");
// A tool reply is a websocket ordering barrier: all earlier presence frames have been consumed.
await McpProbe("tools/call", "{\"name\":\"phone_observe\",\"arguments\":{}}");
Check(!presenceEvents.Reader.TryRead(out _), "discovery and ping do not reactivate a disconnected agent");
var initializedEnvelope = await McpProbe("initialize", "{\"clientInfo\":{\"name\":\"Codex\"}}");
await McpProbe("tools/call", "{\"name\":\"phone_observe\",\"arguments\":{}}");
Check(presenceEvents.Reader.TryRead(out var connectedAgent) && connectedAgent == external["id"] + ":" + mcpClient.DefaultRequestHeaders.GetValues("Mcp-Session-Id").Single(), "initialize creates an isolated client identity");
var originalTransportId = mcpClient.DefaultRequestHeaders.GetValues("Mcp-Session-Id").Single();
var resumeEnvelope = await McpProbe("initialize", new JsonObject { ["clientInfo"] = new JsonObject { ["name"] = "Resumed" },
    ["_meta"] = new JsonObject { ["remotetool/resume_token"] = initializedEnvelope["result"]!["_meta"]!["remotetool"]!["resume_token"]!.ToString() } }.ToJsonString());
Check(mcpClient.DefaultRequestHeaders.GetValues("Mcp-Session-Id").Single() != originalTransportId, "HTTP resume replaces transport");
Check(resumeEnvelope["result"]!["_meta"]!["remotetool"]!["agent_id"]!.ToString() == originalTransportId, "HTTP resume preserves logical identity");
await McpProbe("tools/call", "{\"name\":\"phone_observe\",\"arguments\":{}}");
Check(presenceEvents.Reader.TryRead(out var resumedAgent) && resumedAgent == connectedAgent, "same phone owner after HTTP resume");
using (var staleRequest = new HttpRequestMessage(HttpMethod.Post, mcpUrl)) {
    staleRequest.Headers.Add("Mcp-Session-Id", originalTransportId);
    staleRequest.Content = new StringContent("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"ping\"}", Encoding.UTF8, "application/json");
    using var staleResponse = await mcpClient.SendAsync(staleRequest);
    Check(staleResponse.StatusCode == System.Net.HttpStatusCode.NotFound, "old HTTP session is fenced");
}
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
using (var cleanupHttp = new HttpClient()) {
    cleanupHttp.DefaultRequestHeaders.Authorization = new("Bearer", external["token"]!.ToString());
    async Task<(string Sid, string Resume)> StartOwner() {
        using var response = await cleanupHttp.PostAsync(mcpUrl, new StringContent("""{"jsonrpc":"2.0","id":40,"method":"initialize","params":{}}""", Encoding.UTF8, "application/json"));
        var result = JsonNode.Parse(await response.Content.ReadAsStringAsync())!["result"]!;
        Check(result["_meta"]!["remotetool"]!["self_cleanup"]!.ToString() == "resume-token", "initialize advertises self cleanup");
        return (response.Headers.GetValues("Mcp-Session-Id").Single(), result["_meta"]!["remotetool"]!["resume_token"]!.ToString());
    }
    var ownerA = await StartOwner(); var ownerB = await StartOwner();
    using var noOwner = await cleanupHttp.DeleteAsync(mcpUrl);
    Check((int)noOwner.StatusCode == 400, "binding token alone cannot close every owner");
    using var delete = new HttpRequestMessage(HttpMethod.Delete, mcpUrl);
    delete.Headers.Add("X-RemoteTool-Resume-Token", ownerA.Resume);
    using var deleted = await cleanupHttp.SendAsync(delete);
    Check((int)deleted.StatusCode == 204, "bound token and resume credential close self without Web JWT");
    using var ping = new HttpRequestMessage(HttpMethod.Post, mcpUrl) {
        Content = new StringContent("""{"jsonrpc":"2.0","id":41,"method":"ping"}""", Encoding.UTF8, "application/json")
    };
    ping.Headers.Add("Mcp-Session-Id", ownerB.Sid);
    using var alive = await cleanupHttp.SendAsync(ping);
    Check(alive.IsSuccessStatusCode, "self cleanup preserves other owner");
}
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
var disconnected = (await gateway.Invoke("phone-test", "phone_observe", new(), "offline-direct", false, default))["structuredContent"]!;
Check(disconnected["code"]!.ToString() == "phone_offline" && disconnected["execution"]!.ToString() == "not_started", "offline calls have definite nonexecution");
using (var dropped = new ClientWebSocket()) {
    await dropped.ConnectAsync(new Uri(address), default);
    await dropped.SendAsync(Encoding.UTF8.GetBytes(hello.ToJsonString()), WebSocketMessageType.Text, true, default);
    await Receive(dropped);
    var pendingCall = gateway.Invoke("phone-test", "phone_observe", new(), "drop-pending", false, default);
    await Receive(dropped);
    var timer = System.Diagnostics.Stopwatch.StartNew();
    await dropped.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, "test disconnect", default);
    var missing = (await pendingCall.WaitAsync(disconnectBudget))["structuredContent"]!;
    var elapsed = timer.Elapsed.TotalMilliseconds;
    Check(elapsed < disconnectBudget.TotalMilliseconds && missing["execution"]!.ToString() == "unknown" && missing["delivery_state"]!.ToString() == "sent", "disconnect immediately completes pending sent calls without pretending nonexecution");
    Console.WriteLine($"Relay pending: transport=loopback-ws state=sent elapsed_ms={elapsed:F3} budget_ms={disconnectBudget.TotalMilliseconds:F0}");
}
for (var repetition = 0; repetition < 3; repetition++) {
    foreach (var expectedDelivery in new[] { "not_sent", "possibly_sent", "sent" }) {
        var isolated = new PhoneGateway(Path.Combine(folder, "fault-" + repetition + "-" + expectedDelivery));
        using var fake = new FaultingPhoneSocket(hello, failInvokeSend: expectedDelivery == "possibly_sent");
        var attached = isolated.Attach(fake, default);
        await fake.Ready.Task.WaitAsync(TimeSpan.FromSeconds(2));
        using var beforeSend = new CancellationTokenSource();
        if (expectedDelivery == "not_sent") beforeSend.Cancel();
        var timer = System.Diagnostics.Stopwatch.StartNew();
        var pending = isolated.Invoke("phone-test", "phone_observe", new(), "fault-op", false, beforeSend.Token);
        if (expectedDelivery != "not_sent") await fake.InvokeReceived.Task.WaitAsync(disconnectBudget);
        // This fixture returns completed send Tasks. Invoke has therefore advanced
        // past sendCompleted=true before returning its pending Task to this caller.
        if (expectedDelivery == "sent") { timer.Restart(); fake.Abort(); }
        var data = (await pending.WaitAsync(disconnectBudget))["structuredContent"]!;
        var elapsed = timer.Elapsed.TotalMilliseconds;
        Check(data["delivery_state"]!.ToString() == expectedDelivery, "deterministic " + expectedDelivery);
        Check(data["execution"]!.ToString() == (expectedDelivery == "not_sent" ? "not_started" : "unknown"), "execution matches send evidence");
        Check(data["relay_metrics"]!["send_completed"]!.GetValue<bool>() == (expectedDelivery == "sent"), "send completion flag matches injected boundary");
        Check(elapsed < disconnectBudget.TotalMilliseconds, "isolated disconnect/cancellation wakes pending within the common budget");
        Console.WriteLine($"Relay pending: transport=isolated-mock state={expectedDelivery} repetition={repetition + 1} elapsed_ms={elapsed:F3} budget_ms={disconnectBudget.TotalMilliseconds:F0}");
        fake.Abort();
        await attached.WaitAsync(TimeSpan.FromSeconds(1));
    }
}
Console.WriteLine($"Deterministic relay states: 3 repetitions each of not_sent, possibly_sent, sent; common pending budget <{disconnectBudget.TotalMilliseconds:F0}ms.");
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
