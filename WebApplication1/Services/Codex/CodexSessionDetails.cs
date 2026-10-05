using System.Text.Json;
using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

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
        JsonObject context = Obj(("available", false)), question = null;
        var pendingQuestions = new Dictionary<string, JsonObject>();
        if (string.IsNullOrWhiteSpace(path)) return Obj(("context", context), ("question", question));
        try {
            if (!Path.GetFullPath(path).StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return Obj(("context", context));
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var reader = new StreamReader(stream);
            while (reader.ReadLine() is { } line) {
                try {
                    if (JsonNode.Parse(line) is not JsonObject record || record["payload"] is not JsonObject payload) continue;
                    if (record.S("type") == "compacted") context = Obj(("available", false));
                    if (record.S("type") == "token_usage_record" && payload["usage"] is { } usage) context["tokens"] = usage.L("total_tokens");
                    if (record.S("type") == "event_msg" && payload.S("type") == "token_count" && payload["info"] is { } info) {
                        var last = info["last_token_usage"]; var limit = info.L("model_context_window");
                        context = Obj(("available", last != null), ("tokens", last.L("total_tokens")), ("limit", limit > 0 ? (object)limit : null), ("estimated", false));
                    }
                    if (record.S("type") != "response_item") continue;
                    if (payload.S("type") == "message" && payload.S("role") == "user") { question = null; pendingQuestions.Clear(); }
                    if (payload.S("type") == "function_call" && payload.S("name").Split('.').Last() == "request_user_input_async") {
                        var arguments = JsonNode.Parse(payload.S("arguments", "{}"));
                        var questions = Questions(arguments?["questions"]);
                        var id = payload.S("call_id", Hash(line)[..24]);
                        if (questions.Count > 0) pendingQuestions[id] = Obj(("request_id", id), ("questions", questions), ("async", true));
                    }
                    if (payload.S("type") == "function_call_output" && pendingQuestions.Remove(payload.S("call_id"), out var candidate)) {
                        // A failed/unrecognized tool call is not an actionable question.
                        var output = JsonNode.Parse(payload.S("output", "{}"));
                        if (output.B("accepted")) question = candidate;
                    }
                } catch (Exception error) when (error is JsonException or InvalidOperationException) { }
            }
            if (context.L("tokens") > 0) context["available"] = true;
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
        return Obj(("context", context), ("question", question));
    }
}
