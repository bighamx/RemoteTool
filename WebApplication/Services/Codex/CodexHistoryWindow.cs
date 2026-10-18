using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

/** Native pages are newest-first; only visible messages spend the history budget. */
internal sealed class CodexHistoryWindow(int limit, string fromId = null, string olderBefore = null)
{
    private readonly string anchor = olderBefore ?? fromId;
    private bool found = string.IsNullOrEmpty(olderBefore ?? fromId);
    private int remaining = Math.Clamp(limit, 1, 500);
    public bool Done { get; private set; }
    public static long MessageId(string id) => Convert.ToInt64(Hash(id)[..14], 16) + 1;
    public static JsonObject Request(string session, string cursor = "") {
        var request = Obj(("threadId", session), ("limit", 20), ("itemsView", "full"), ("sortDirection", "desc"));
        if (cursor.Length > 0) request["cursor"] = cursor;
        return request;
    }
    private void Visit(string id, bool visible) {
        if (!found && id == anchor) {
            found = true;
            if (olderBefore == null) Done = true;
            return;
        }
        if (found && visible && --remaining <= 0) Done = true;
    }
    public void Add(JsonNode turn) {
        Visit(CompletedToolHistory.Summary(turn.S("id"), 0, null).L("id").ToString(), false);
        foreach (var item in turn.A("items").Reverse()) {
            if (Done) break;
            var type = item.S("type");
            var visible = type == "contextCompaction" || type == "userMessage" && item.A("content").Count > 0 ||
                type == "agentMessage" && item.S("text").Length > 0;
            if (visible) Visit(MessageId(item.S("id")).ToString(), true);
        }
    }
}
