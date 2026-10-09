using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using SQLite;

namespace RemoteTool.WebApi.Services;

internal static class LatestSessionPreview
{
    internal static string ContentText(JsonArray parts) {
        var text = string.Join("\n", parts.OfType<JsonObject>().Where(part => part["text"] != null).Select(part => part["text"]!.ToString()));
        if (string.IsNullOrWhiteSpace(text) && parts.OfType<JsonObject>().Any(part => part["type"]?.ToString() is "image" or "localImage" or "image_url" or "input_image")) return "图片附件";
        return text;
    }
    private sealed class PreviewRow {
        [Column("session")] public string Session { get; set; }
        [Column("content")] public string Content { get; set; }
    }

    public static string Text(string content, bool codex = false) {
        if (string.IsNullOrWhiteSpace(content)) return "";
        var text = content.Replace("\r\n", "\n").Trim();
        try {
            if (text.StartsWith('[') && JsonNode.Parse(text) is JsonArray parts && parts.OfType<JsonObject>().Any(part => part["type"]?.ToString() is "text" or "input_text" or "image" or "image_url" or "input_image"))
                text = ContentText(parts);
            else if (codex && text.StartsWith('{') && JsonNode.Parse(text) is JsonObject item && item["type"]?.ToString() == "userMessage")
                text = ContentText(item["content"] as JsonArray ?? new());
        } catch (JsonException) { }
        if (text.TrimStart().StartsWith("<heartbeat>", StringComparison.Ordinal)) return "";
        const string close = "[/OUT-OF-BAND USER MESSAGE]";
        if (text.StartsWith("[OUT-OF-BAND USER MESSAGE", StringComparison.Ordinal) && text.EndsWith(close, StringComparison.Ordinal)) {
            var start = text.IndexOf("]\n", StringComparison.Ordinal);
            if (start >= 0) text = text[(start + 2)..^close.Length].Trim();
        }
        var marker = text.IndexOf("\n\n[ChuckieHelper 持久附件]", StringComparison.Ordinal);
        if (marker >= 0) text = text[..marker];
        marker = text.IndexOf("\n\n附件文件：\n", StringComparison.Ordinal);
        if (marker >= 0) text = text[..marker];
        if (text.StartsWith("<send_user_message_question_reply>", StringComparison.Ordinal)) {
            try {
                var json = text["<send_user_message_question_reply>".Length..].Split("</send_user_message_question_reply>", 2)[0];
                if (JsonNode.Parse(json) is JsonArray replies) text = string.Join("；", replies.OfType<JsonObject>().Select(reply => "回答：" + reply["answer"]?.ToString()));
            } catch (JsonException) { }
        }
        text = Regex.Replace(text, "\\s+", " ").Trim();
        return text.Length > 360 ? text[..360] : text;
    }

    public static IReadOnlyDictionary<string, string> Read(string database, IEnumerable<string> sessions, bool codex) {
        var ids = sessions.Where(id => !string.IsNullOrWhiteSpace(id)).Distinct().Take(100).ToArray();
        if (ids.Length == 0 || !File.Exists(database)) return new Dictionary<string, string>();
        try {
            SQLitePCL.Batteries_V2.Init();
            using var connection = new SQLiteConnection(database, SQLiteOpenFlags.ReadOnly | SQLiteOpenFlags.FullMutex);
            var parameters = string.Join(',', ids.Select(_ => "?"));
            var sql = codex
                ? $"SELECT thread_id AS session, item_json AS content FROM (SELECT thread_id,item_json,ROW_NUMBER() OVER(PARTITION BY thread_id ORDER BY rollout_ordinal DESC,created_at_ms DESC) AS position FROM thread_items WHERE item_type='userMessage' AND thread_id IN ({parameters})) WHERE position<=32 ORDER BY session,position"
                : $"SELECT session_id AS session, content FROM (SELECT session_id,content,ROW_NUMBER() OVER(PARTITION BY session_id ORDER BY id DESC) AS position FROM messages WHERE role='user' AND session_id IN ({parameters})) WHERE position<=32 ORDER BY session,position";
            var result = ids.ToDictionary(id => id, _ => "");
            foreach (var row in connection.Query<PreviewRow>(sql, ids.Cast<object>().ToArray())) {
                if (result[row.Session].Length > 0) continue;
                var text = Text(row.Content, codex);
                if (text.Length > 0) result[row.Session] = text;
            }
            return result;
        } catch (Exception error) when (error is SQLiteException or IOException or UnauthorizedAccessException or DllNotFoundException or TypeInitializationException) {
            return new Dictionary<string, string>();
        }
    }
}
