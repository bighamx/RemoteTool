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

    public static JsonNode Reduce(JsonNode document, bool running, long startedAt = 0, string session = null, string revision = "")
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
        var cacheIds = new HashSet<string> { "active" }; var activeTools = new JsonArray();
        for (var index = 0; index < groups.Count; index++) {
            var group = groups[index];
            foreach (var row in group) {
                if (index >= active) { output.Add(row?.DeepClone()); continue; }
                if (row?["role"]?.ToString() == "tool") continue;
                if (row?["role"]?.ToString() == "assistant" && string.IsNullOrWhiteSpace(row?["content"]?.ToString()) &&
                    row?["attachments"] is not JsonArray { Count: > 0 }) continue;
                var copy = row?.DeepClone();
                if (copy is JsonObject obj) { obj.Remove("tool_calls"); obj.Remove("tool_call_id"); }
                output.Add(copy);
            }
            var tools = ToolHistoryCache.HermesTools(group, index >= active);
            if (index < active && tools.Count > 0) {
                var summary = ToolHistoryCache.Summary(group[0]?["id"]?.ToString() ?? index.ToString(), tools, group[^1]?["timestamp"]);
                var id = summary["id"]!.ToString(); cacheIds.Add(id);
                if (session != null) ToolHistoryCache.Save("hermes", session, id, tools, false, revision);
                output.Add(summary);
            } else if (index >= active) foreach (var tool in tools) activeTools.Add(tool?.DeepClone());
        }
        if (session != null) {
            if (!running && activeTools.Count == 0 && groups.Count > 0) activeTools = ToolHistoryCache.HermesTools(groups[^1], false);
            ToolHistoryCache.Save("hermes", session, "active", activeTools, running, revision);
            ToolHistoryCache.Keep("hermes", session, cacheIds);
        }
        document["data"] = output;
        return document;
    }
}
