using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Services;

public static class ToolHistoryCache
{
    private sealed record Entry(JsonArray Tools, string Revision, string Path, string Stamp, bool Running, DateTime At);
    private static readonly object gate = new();
    private static readonly Dictionary<string, Entry> entries = new();
    private static string Key(string agent, string session, string id) => agent + ":" + session + ":" + id;
    public static string Stamp(string path) {
        if (string.IsNullOrEmpty(path)) return "";
        try { var file = new FileInfo(path); return file.Exists ? file.Length + ":" + file.LastWriteTimeUtc.Ticks : "missing"; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException) { return "unavailable"; }
    }
    public static bool FileChange(string name) => name.Split('.').Last().ToLowerInvariant() is
        "filechange" or "apply_patch" or "write_file" or "edit_file" or "file_write" or "file_edit" or "patch";
    public static string Description(JsonNode value) {
        var text = value?.ToString() ?? "";
        if (text.Length <= 256 * 1024) try {
            if (JsonNode.Parse(text) is JsonObject args)
                text = (args["cmd"] ?? args["command"] ?? args["path"] ?? args["file_path"] ?? args["query"] ?? args["description"])?.ToString()
                    ?? "参数：" + string.Join("、", args.Select(pair => pair.Key).Take(4));
        } catch (System.Text.Json.JsonException) { }
        if (text.Contains("*** Begin Patch", StringComparison.Ordinal)) {
            var paths = Regex.Matches(text, @"(?m)^\*\*\* (?:Update|Add|Delete) File: (.+)$").Select(match => match.Groups[1].Value.Trim()).ToArray();
            text = paths.Length > 0 ? string.Join("、", paths.Take(3)) + (paths.Length > 3 ? $" 等 {paths.Length} 个文件" : "") : "应用文件补丁";
        }
        text = Regex.Replace(text.Length > 4096 ? text[..4096] : text, @"\s+", " ").Trim();
        return text.Length > 160 ? text[..160] + "…" : text;
    }
    public static JsonObject Tool(string id, string name, JsonNode description, string status) => new() {
        ["id"] = id, ["tool"] = name, ["category"] = FileChange(name) ? "file_change" : "tool",
        ["description"] = Description(description), ["status"] = status
    };
    public static JsonArray HermesTools(IEnumerable<JsonNode> rows, bool running) {
        var source = rows.ToArray(); var result = new Dictionary<string, JsonObject>();
        foreach (var row in source.Where(row => row?["role"]?.ToString() == "assistant")) {
            JsonArray calls = row?["tool_calls"] as JsonArray;
            if (calls == null) try { calls = JsonNode.Parse(row?["tool_calls"]?.ToString() ?? "null") as JsonArray; } catch (System.Text.Json.JsonException) { }
            if (calls == null) continue;
            for (var index = 0; index < calls.Count; index++) {
                var call = calls[index]; var id = call?["id"]?.ToString() ?? row?["id"] + ":" + index;
                var output = source.LastOrDefault(item => item?["role"]?.ToString() == "tool" && item?["tool_call_id"]?.ToString() == id);
                var status = output == null ? running ? "running" : "unknown" : "completed";
                if (output?["finish_reason"]?.ToString() == "error") status = "failed";
                if (output?["content"]?.ToString() is string content && content.Length <= 256 * 1024) try {
                    if (JsonNode.Parse(content) is JsonObject value && (value["error"] != null || value["isError"]?.ToString() == "true" ||
                        int.TryParse((value["exit_code"] ?? value["exitCode"])?.ToString(), out var code) && code != 0)) status = "failed";
                } catch (System.Text.Json.JsonException) { }
                result[id] = Tool(id, call?["function"]?["name"]?.ToString() ?? "工具", call?["function"]?["arguments"], status);
            }
        }
        foreach (var row in source.Where(row => row?["role"]?.ToString() == "tool")) {
            var id = row?["tool_call_id"]?.ToString() ?? row?["id"]?.ToString();
            if (id != null && !result.ContainsKey(id)) result[id] = Tool(id, row?["name"]?.ToString() ?? "工具", JsonValue.Create("原调用参数不可用"), "unknown");
        }
        return new JsonArray(result.Values.Select(tool => (JsonNode)tool).ToArray());
    }
    public static void Save(string agent, string session, string id, JsonArray tools, bool running, string revision = "", string path = "") {
        lock (gate) {
            if (entries.Count >= 512 && !entries.ContainsKey(Key(agent, session, id))) entries.Remove(entries.MinBy(pair => pair.Value.At).Key);
            entries[Key(agent, session, id)] = new((JsonArray)tools.DeepClone(), revision, path, Stamp(path), running, DateTime.UtcNow);
        }
    }
    public static void Keep(string agent, string session, HashSet<string> ids) {
        var prefix = agent + ":" + session + ":";
        lock (gate) foreach (var key in entries.Keys.Where(key => key.StartsWith(prefix) && !ids.Contains(key[prefix.Length..])).ToArray()) entries.Remove(key);
    }
    public static JsonObject Read(string agent, string session, string id, int offset, bool hit, string revision = null) {
        lock (gate) {
            if (!entries.TryGetValue(Key(agent, session, id), out var entry) || DateTime.UtcNow - entry.At > TimeSpan.FromMinutes(20) ||
                revision != null && revision != entry.Revision || Stamp(entry.Path) != entry.Stamp ||
                entry.Running && DateTime.UtcNow - entry.At > TimeSpan.FromSeconds(2)) return null;
            var start = Math.Clamp(offset, 0, entry.Tools.Count); var size = 50;
            var changed = entry.Tools.Count(tool => tool?["category"]?.ToString() == "file_change");
            return new JsonObject { ["data"] = new JsonArray(entry.Tools.Skip(start).Take(size).Select(tool => tool?.DeepClone()).ToArray()),
                ["total"] = entry.Tools.Count, ["file_changes"] = changed, ["tools"] = entry.Tools.Count - changed,
                ["running"] = entry.Running, ["cache_hit"] = hit, ["has_more"] = start + size < entry.Tools.Count,
                ["version"] = entry.Revision + ":" + entry.Stamp + ":" + entry.Running };
        }
    }
    public static JsonObject Summary(string identity, JsonArray tools, JsonNode timestamp) {
        var result = CompletedToolHistory.Summary(identity, tools.Count, timestamp);
        var changed = tools.Count(tool => tool?["category"]?.ToString() == "file_change");
        result["file_changes"] = changed; result["tools"] = tools.Count - changed;
        result["content"] = $"工具 {tools.Count - changed} 次 · 文件修改 {changed} 次";
        return result;
    }
}
