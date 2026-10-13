using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>
/// Hermes delivers two runtime notes on the user-message channel and (through the API
/// projection) they end up in the content our history endpoint returns, so a client that
/// shows the raw text displays Hermes' own bookkeeping as if the user had typed it:
///
///   [System: This conversation is now being answered on a different interface: ...]
///   [System: The skills available in this conversation changed after its system prompt was built...]
///
/// Those are addressed to the model, not to the user (the desktop client hides them too).
/// Strip the trailing note blocks and restore the authored text. The authored part is
/// everything before the first note marker, so the strip is a simple, safe truncation —
/// it cannot eat a genuine user message because the markers are Hermes' own bracketed
/// prefix and a user typing them would still keep their own text before them.
/// </summary>
public static class AgentRuntimeNotes
{
    private static readonly string[] Markers =
    {
        "[System: This conversation is now being answered on a different interface:",
        "[System: The skills available in this conversation changed",
        "[System: These tools stay listed for this conversation",
    };

    public static string Strip(string content)
    {
        if (string.IsNullOrEmpty(content)) return content ?? "";
        var cut = -1;
        foreach (var marker in Markers)
        {
            var index = content.IndexOf(marker, System.StringComparison.Ordinal);
            if (index >= 0 && (cut < 0 || index < cut)) cut = index;
        }
        return cut < 0 ? content : content[..cut].TrimEnd();
    }

    public static JsonNode Present(JsonNode document)
    {
        if (document?["data"] is not JsonArray rows) return document;
        foreach (var row in rows)
        {
            if (row is not JsonObject item) continue;
            if (item["role"]?.ToString() != "user") continue;
            var content = item["content"]?.ToString();
            if (string.IsNullOrEmpty(content)) continue;
            var stripped = Strip(content);
            if (stripped == content) continue;
            // An empty result means the message was nothing but a note: hide it rather than
            // leave an empty bubble (the visible-content filter on both clients drops it).
            item["content"] = stripped;
        }
        return document;
    }
}
