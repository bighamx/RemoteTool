using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexToolEvent {
    internal static (string Key, object Value)[] Fields(JsonNode item, string name, string preview) => new (string, object)[] {
        ("tool", name), ("item_id", item.S("id")), ("call_id", item.S("callId")),
        ("preview", preview), ("error", item.S("status") is "failed" or "declined" ||
            item["exitCode"] != null && item.L("exitCode") != 0)
    };

    internal static string Status(JsonNode item, JsonNode turn) {
        if (item["exitCode"] != null && item.L("exitCode") != 0) return "failed";
        return item.S("status") switch {
            "failed" => "failed",
            "declined" or "interrupted" => "cancelled",
            "completed" => "completed",
            _ when item.B("completed") => "completed",
            _ => turn.S("status") == "inProgress" ? "running" : "unknown"
        };
    }
}
