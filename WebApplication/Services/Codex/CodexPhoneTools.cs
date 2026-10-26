using System.Net.Http.Headers;
using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal sealed partial class CodexAgent {
    private JsonObject ReadPhoneBinding(string id) => Read(Path.Combine(folder, "phone-tools", id + ".json"));
    private static JsonArray PhoneDynamicTools(JsonObject binding) => new(binding.A("tools").OfType<JsonObject>().Select(tool => (JsonNode)Obj(
        ("type", "function"), ("name", tool.S("name")), ("description", tool.S("description")), ("inputSchema", tool["inputSchema"]))).ToArray());
    private async Task HandlePhoneCall(JsonObject message, CodexRpc origin, string session) {
        JsonObject response;
        try {
            var binding = ReadPhoneBinding(session);
            var p = message["params"]!; var name = p.S("tool", p.S("name"));
            if (binding.S("mode") != "tools" || !binding.A("tools").Any(t => t.S("name") == name)) throw new InvalidOperationException("Phone tool not enabled for this session");
            var args = p["arguments"] is JsonValue value && value.TryGetValue<string>(out var text) ? JsonNode.Parse(text) : p["arguments"];
            using var client = new HttpClient { Timeout = TimeSpan.FromSeconds(name == "phone_manage_app" && args?.S("action") == "install" ? 130 : 45) };
            using var request = new HttpRequestMessage(HttpMethod.Post, binding.S("url"));
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", binding.S("token"));
            request.Headers.Add("X-Phone-Session", session);
            var key = "codex-" + Hash(session + ":" + p.S("turnId") + ":" + p.S("callId", p.S("itemId", message.S("id"))));
            request.Content = new StringContent(Obj(("tool", name), ("arguments", args ?? new JsonObject()), ("request_id", key)).ToJsonString(), System.Text.Encoding.UTF8, "application/json");
            using var result = await client.SendAsync(request); result.EnsureSuccessStatusCode();
            var payload = JsonNode.Parse(await result.Content.ReadAsStringAsync())!;
            var content = new JsonArray();
            foreach (var item in payload.A("content")) {
                if (item.S("type") == "image") content.Add(Obj(("type", "inputImage"), ("imageUrl", "data:" + item.S("mimeType") + ";base64," + item.S("data"))));
                else if (item.S("type") == "text") content.Add(Obj(("type", "inputText"), ("text", item.S("text"))));
            }
            response = Obj(("success", !payload.B("isError")), ("contentItems", content));
        } catch (Exception error) {
            response = Obj(("success", false), ("contentItems", new JsonArray(Obj(("type", "inputText"), ("text", "Phone result unavailable: " + error.Message + ". Observe before retrying.")))));
        }
        try { await origin.Write(Obj(("id", message["id"]), ("result", response))); }
        catch (Exception error) when (error is CodexError or IOException or ObjectDisposedException) { /* Owner ended while the phone operation was running. */ }
    }
}
