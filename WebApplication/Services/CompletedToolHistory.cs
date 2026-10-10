using System.Security.Cryptography;
using System.Text;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

public static class CompletedToolHistory
{
    public static JsonObject Summary(string identity, int count, JsonNode timestamp) => new() {
        ["id"] = BitConverter.ToUInt64(SHA256.HashData(Encoding.UTF8.GetBytes("tools:" + identity)), 0) & 0x001FFFFFFFFFFFFFUL,
        ["role"] = "system", ["type"] = "toolSummary", ["content"] = $"调用了 {count} 次工具",
        ["tool_count"] = count, ["timestamp"] = timestamp?.DeepClone(), ["editable"] = false
    };

    public static JsonNode Reduce(JsonNode document, bool running, long startedAt = 0)
    {
        if (document?["data"] is not JsonArray rows) return document;
        var output = new JsonArray();
        var groups = new List<List<JsonNode>>();
        foreach (var row in rows) {
            if (groups.Count == 0 || row?["role"]?.ToString() == "user") groups.Add(new());
            groups[^1].Add(row);
        }
        var active = groups.Count;
        if (running && groups.Count > 0) {
            active = groups.Count - 1;
            if (startedAt > 0) {
                var found = groups.FindIndex(group => group.Any(row => {
                    if (!double.TryParse(row?["timestamp"]?.ToString(), System.Globalization.NumberStyles.Any,
                        System.Globalization.CultureInfo.InvariantCulture, out var time)) return false;
                    return (time < 100000000000 ? time * 1000 : time) >= startedAt;
                }));
                if (found >= 0) active = found;
            }
        }
        for (var index = 0; index < groups.Count; index++) {
            var group = groups[index]; var calls = new HashSet<string>(); var fallback = 0;
            foreach (var row in group) {
                if (row?["role"]?.ToString() == "tool") {
                    var key = row?["tool_call_id"]?.ToString();
                    if (!string.IsNullOrEmpty(key)) calls.Add(key); else fallback++;
                }
                JsonArray tools = row?["tool_calls"] as JsonArray;
                if (tools == null && row?["tool_calls"] is JsonValue value && value.TryGetValue<string>(out var text)) {
                    try { tools = JsonNode.Parse(text) as JsonArray; } catch (System.Text.Json.JsonException) { }
                }
                if (tools != null) foreach (var tool in tools) calls.Add(tool?["id"]?.ToString() ?? row?["id"] + ":" + fallback++);
                if (index >= active) { output.Add(row?.DeepClone()); continue; }
                if (row?["role"]?.ToString() == "tool") continue;
                if (row?["role"]?.ToString() == "assistant" && string.IsNullOrWhiteSpace(row?["content"]?.ToString()) &&
                    row?["attachments"] is not JsonArray { Count: > 0 }) continue;
                var copy = row?.DeepClone();
                if (copy is JsonObject obj) { obj.Remove("tool_calls"); obj.Remove("tool_call_id"); }
                output.Add(copy);
            }
            var count = Math.Max(calls.Count, fallback);
            if (index < active && count > 0)
                output.Add(Summary(group[0]?["id"]?.ToString() ?? index.ToString(), count, group[^1]?["timestamp"]));
        }
        document["data"] = output;
        return document;
    }
}
