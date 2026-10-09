using System.Text.Json;
using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexSessionDetails
{
    public static JsonArray Questions(JsonNode source) {
        var rows = new JsonArray();
        if (source is not JsonArray questions) return rows;
        foreach (var question in questions.Take(5)) {
            var text = question.S("question", question.S("title"));
            if (text.Length == 0) continue;
            var options = new JsonArray();
            foreach (var option in (question?["options"] as JsonArray ?? question?["choices"] as JsonArray ?? new JsonArray()).Take(8)) {
                var label = option is JsonObject ? option.S("label") : option?.ToString();
                if (!string.IsNullOrWhiteSpace(label)) options.Add(Obj(("label", label), ("description", option is JsonObject ? option.S("description") : "")));
            }
            rows.Add(Obj(("id", question.S("id", "q" + rows.Count)), ("question", text), ("options", options), ("isSecret", question.B("isSecret"))));
        }
        return rows;
    }
    public static JsonObject Read(string home, string path) {
        var snapshot = CodexRolloutSnapshot.Read(home, path);
        return Obj(("context", snapshot["context"]), ("question", snapshot["question"]));
    }
}
