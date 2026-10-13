using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

/** Compaction is a history change, not a tool call. Both transports use the same event. */
internal static class CodexCompactionEvent
{
    internal static JsonObject Create(JsonNode item, bool completed)
    {
        if (item.S("type") != "contextCompaction") return null;
        var failed = item.S("status") is "failed" or "declined";
        return Obj(("event", completed ? failed ? "context.compaction.failed" : "context.compaction.completed" : "context.compaction.started"),
            ("item_id", item.S("id")), ("timestamp", DateTimeOffset.UtcNow.ToUnixTimeMilliseconds()));
    }
}
