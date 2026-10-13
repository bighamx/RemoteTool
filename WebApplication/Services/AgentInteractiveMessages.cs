using System.Text;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>
/// Hermes stores clarify / request_user_input turns as role "tool" rows whose content is the
/// interaction JSON (the question, the offered choices and the answer). Both clients only
/// render user/assistant rows, so those turns were invisible on the phone and in the web
/// workbench even when the raw row reached them.
///
/// The row is surfaced as a dedicated "interaction" message instead of a plain user turn:
/// it is not authored text, and drawing it like the user's own message is misleading. The
/// row keeps its id (so ordering, keys and paging anchors still work) and carries the parsed
/// question/answer plus the chosen option under "interactions" so each client can draw a
/// proper card.
/// </summary>
public static class AgentInteractiveMessages
{
    public static JsonNode Present(JsonNode document)
    {
        if (document?["data"] is not JsonArray rows) return document;
        foreach (var row in rows)
        {
            if (row is not JsonObject item) continue;
            if (item["role"]?.ToString() != "tool" || !AgentHistoryWindow.Interactive(item)) continue;
            var entries = Parse(item["content"]?.ToString());
            if (entries.Count == 0) continue;
            item["role"] = "system";
            item["type"] = "interaction";
            item["content"] = string.Join("\n", entries.Select(entry => entry.Text));
            item["interactions"] = new JsonArray(entries.Select(entry => (JsonNode)entry.Json).ToArray());
            item["editable"] = false;
            item.Remove("tool_name");
            item.Remove("tool_calls");
            item.Remove("tool_call_id");
        }
        return document;
    }

    private sealed record Entry(string Text, JsonObject Json);

    private static List<Entry> Parse(string content)
    {
        var entries = new List<Entry>();
        if (string.IsNullOrWhiteSpace(content)) return entries;
        JsonNode parsed;
        try { parsed = JsonNode.Parse(content); }
        catch (System.Text.Json.JsonException) { return entries; }
        if (parsed?["responses"] is not JsonArray responses) return entries;
        foreach (var value in responses)
        {
            if (value is not JsonObject response) continue;
            var question = response["question"]?.ToString() ?? "";
            var answer = response["user_response"]?.ToString() ?? "";
            var status = response["status"]?.ToString() ?? "";
            var choices = response["choices_offered"] as JsonArray;
            var item = new JsonObject {
                ["question"] = question,
                ["answer"] = answer,
                ["status"] = status.Length > 0 ? status : (answer.Length > 0 ? "answered" : "pending"),
                ["choices"] = choices is null ? new JsonArray() : new JsonArray(choices.Select(choice => choice?.DeepClone()).ToArray()),
            };
            var text = question.Length > 0 ? "提问：" + question : "";
            var reply = answer.Length > 0 ? answer : status switch { "answered" => "已回答", "declined" => "已取消", _ => "未回答" };
            if (reply.Length > 0) text += (text.Length > 0 ? "\n" : "") + "回答：" + reply;
            entries.Add(new Entry(text, item));
        }
        return entries;
    }
}
