using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexToolHistory
{
    internal static JsonArray Tools(JsonNode turn) {
        var tools = new JsonArray();
        foreach (var item in turn.A("items")) {
            var kind = item.S("type");
            if (kind is not ("commandExecution" or "fileChange" or "mcpToolCall" or "dynamicToolCall" or "webSearch" or "imageView" or "imageGeneration" or "collabAgentToolCall")) continue;
            var name = item.S("tool", item.S("name", kind switch { "commandExecution" => "终端", "fileChange" => "fileChange", "webSearch" => "搜索", "imageView" => "查看图片", _ => kind }));
            JsonNode description = item["command"] ?? item["query"] ?? item["arguments"] ?? item["path"];
            if (kind == "fileChange") description = JsonValue.Create(string.Join("、", item.A("changes").Take(3).Select(change => change.S("path"))) + (item.A("changes").Count > 3 ? $" 等 {item.A("changes").Count} 个文件" : ""));
            var status = item.S("status") switch { "failed" => "failed", "declined" or "interrupted" => "cancelled", "completed" => "completed", _ => turn.S("status") == "inProgress" ? "running" : "unknown" };
            if (item["exitCode"] != null && item.L("exitCode") != 0) status = "failed";
            tools.Add(ToolHistoryCache.Tool(item.S("id"), name, description, status));
        }
        return tools;
    }
    internal static void Populate(JsonNode thread, string session, string home) {
        var path = CodexPreviewRollout.LatestPath(home, session, thread.S("path"));
        var ids = new HashSet<string> { "active" }; var active = new JsonArray(); var running = false;
        foreach (var turn in thread.A("turns")) {
            var tools = Tools(turn);
            if (turn.S("status") == "inProgress") { running = true; foreach (var tool in tools) active.Add(tool?.DeepClone()); }
            else if (tools.Count > 0) {
                var summary = ToolHistoryCache.Summary(turn.S("id"), tools, null);
                var id = summary["id"]!.ToString(); ids.Add(id);
                ToolHistoryCache.Save("codex", session, id, tools, false, path: path);
            }
        }
        if (!running && active.Count == 0) {
            var last = thread.A("turns").LastOrDefault();
            if (last != null) active = Tools(last);
        }
        ToolHistoryCache.Save("codex", session, "active", active, running, path: path);
        ToolHistoryCache.Keep("codex", session, ids);
    }
}
