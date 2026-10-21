using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>Count visible messages, retain native array order, and expand from a stable row ID.</summary>
public static class AgentHistoryWindow
{
    public static JsonNode Select(JsonNode document, int limit, string fromId = null, string olderBefore = null)
    {
        if (document?["data"] is not JsonArray rows) return document;
        int Find(string id) => string.IsNullOrEmpty(id) ? -1 : Enumerable.Range(0, rows.Count)
            .FirstOrDefault(index => rows[index]?["id"]?.ToString() == id, -1);
        var anchor = Find(olderBefore ?? fromId);
        var start = anchor;
        if (anchor < 0 || olderBefore != null) {
            var end = anchor < 0 ? rows.Count : anchor;
            var remaining = Math.Clamp(limit, 1, 500);
            start = end;
            while (start > 0 && remaining > 0) {
                start--;
                if (Visible(rows[start])) remaining--;
            }
        }
        document["data"] = new JsonArray(rows.Skip(start).Select(row => row?.DeepClone()).ToArray());
        document["has_more"] = string.Equals(document["has_more"]?.ToString(), "true", StringComparison.OrdinalIgnoreCase) || rows.Take(start).Any(Visible);
        document["oldest_id"] = start < rows.Count ? rows[start]?["id"]?.DeepClone() : null;
        return document;
    }

    public static bool Visible(JsonNode row)
    {
        var role = row?["role"]?.ToString();
        if (role == "system") return row?["type"]?.ToString() == "contextCompaction";
        // Interactive tools (clarify, request_user_input) are stored as role "tool", and their
        // prompt + the chosen answer live only there. Dropping them hid the question and answer
        // from both the web workbench and the app. Other tool rows stay hidden (they are noise
        // and are surfaced through the tool-history view instead).
        if (role == "tool") return IsInteractive(row);
        return role is "user" or "assistant" &&
            (!string.IsNullOrWhiteSpace(row?["content"]?.ToString()) || row?["attachments"] is JsonArray { Count: > 0 });
    }

    // A clarify/request_user_input reply is JSON carrying the question and the answer; anything
    // else that merely contains those keys (a tool that echoed them) must not leak its raw output.
    private static bool IsInteractive(JsonNode row)
    {
        var name = row?["tool_name"]?.ToString();
        if (name == "clarify" || name == "request_user_input" || name == "request_user_input_async") return true;
        if (name != "send_user_message_question_reply") return false;
        var content = row?["content"]?.ToString();
        if (string.IsNullOrWhiteSpace(content)) return false;
        try { return JsonNode.Parse(content)?["responses"] is JsonArray; }
        catch (System.Text.Json.JsonException) { return false; }
    }

    /// <summary>True for rows that carry a clarify / request_user_input prompt and answer.</summary>
    public static bool Interactive(JsonNode row) => IsInteractive(row);
}
