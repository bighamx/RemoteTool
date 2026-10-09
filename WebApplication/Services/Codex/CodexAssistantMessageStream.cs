using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

/** Preserve native item boundaries instead of concatenating a turn into one streaming bubble. */
internal sealed class CodexAssistantMessageStream
{
    private readonly Dictionary<string, (string Text, bool Completed)> seen = new();
    public IEnumerable<JsonObject> Update(JsonNode turn) {
        var items = turn.A("items");
        for (var i = 0; i < items.Count; i++) {
            var item = items[i];
            if (item.S("type") != "agentMessage" || item.S("id").Length == 0) continue;
            var id = item.S("id"); var text = item.S("text"); var phase = item.S("phase");
            var previous = seen.GetValueOrDefault(id);
            var completed = turn.S("status") is "completed" or "failed" or "interrupted" || item.B("completed") || i < items.Count - 1;
            if (!seen.ContainsKey(id)) yield return Obj(("event", "message.started"), ("item_id", id), ("phase", phase));
            if (text != (previous.Text ?? "")) {
                var append = text.StartsWith(previous.Text ?? "", StringComparison.Ordinal);
                yield return Obj(("event", append ? "message.delta" : "message.snapshot"), ("item_id", id), ("phase", phase),
                    (append ? "delta" : "text", append ? text[(previous.Text?.Length ?? 0)..] : text));
            }
            if (completed && (!previous.Completed || previous.Text != text)) yield return Obj(("event", "message.completed"), ("item_id", id), ("phase", phase), ("text", text));
            seen[id] = (text, completed);
        }
    }
}
