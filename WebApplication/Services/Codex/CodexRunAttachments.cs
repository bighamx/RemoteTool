using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexRunAttachments
{
    public static string RequestKey(JsonNode item)
    {
        // UserMessageThreadItem uses clientId, including messages added by turn/steer.
        // The outgoing parameter is clientUserMessageId; its name is not echoed back.
        foreach (var name in new[] { "clientId", "clientUserMessageId", "clientRequestId" }) {
            var value = item.S(name);
            if (!string.IsNullOrEmpty(value)) return value;
        }
        return "";
    }

    // Call under the run journal lock. Never associate attachments by message text.
    public static JsonArray Read(JsonObject journal, string session, string key)
    {
        if (string.IsNullOrEmpty(key)) return null;
        foreach (var state in journal.Select(pair => pair.Value).Where(state => state.S("session_id") == session)) {
            var ids = state.S("key") == key ? state["attachment_ids"] as JsonArray
                : state["steering_requests"]?[key]?["attachment_ids"] as JsonArray;
            if (ids != null) return ids.DeepClone().AsArray();
        }
        return null;
    }
}
