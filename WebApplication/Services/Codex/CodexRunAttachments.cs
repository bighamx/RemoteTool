using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexRunAttachments
{
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
