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
        document["has_more"] = rows.Take(start).Any(Visible);
        document["oldest_id"] = start < rows.Count ? rows[start]?["id"]?.DeepClone() : null;
        return document;
    }

    public static bool Visible(JsonNode row)
    {
        var role = row?["role"]?.ToString();
        if (role == "system") return row?["type"]?.ToString() == "contextCompaction";
        return role is "user" or "assistant" &&
            (!string.IsNullOrWhiteSpace(row?["content"]?.ToString()) || row?["attachments"] is JsonArray { Count: > 0 });
    }
}
