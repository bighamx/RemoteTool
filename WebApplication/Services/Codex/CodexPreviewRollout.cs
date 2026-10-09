using System.Text.Json;
using System.Text.Json.Nodes;

namespace ChuckieHelper.WebApi.Services.Codex;

/** Desktop continuation rollouts can use frontend-id_native-session-id names
 * while thread_history still contains the original native session snapshot. */
internal static class CodexPreviewRollout
{
    private static readonly object gate = new();
    private static readonly Dictionary<string, (DateTime Checked, string[] Paths)> aliases = new();
    private static readonly Dictionary<string, (long Length, long Modified, string Text)> texts = new();

    public static string LatestPath(string home, string id, string original, bool requireAlias = false) {
        if (!System.Text.RegularExpressions.Regex.IsMatch(id, "^[a-zA-Z0-9_-]{1,160}$")) return "";
        try {
            var directory = Path.Combine(home, "sessions");
            if (!Directory.Exists(directory)) return "";
            string[] paths;
            lock (gate) {
                if (aliases.TryGetValue(id, out var indexed) && DateTime.UtcNow - indexed.Checked < TimeSpan.FromSeconds(10)) paths = indexed.Paths;
                else {
                    paths = Directory.EnumerateFiles(directory, "rollout-*-" + id + "_*.jsonl", new EnumerationOptions {
                        RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint, IgnoreInaccessible = true
                    }).ToArray();
                    if (aliases.Count > 512) aliases.Clear();
                    aliases[id] = (DateTime.UtcNow, paths);
                }
            }
            if (paths.Length == 0 && requireAlias) return "";
            var candidates = paths.Concat(string.IsNullOrWhiteSpace(original) ? Array.Empty<string>() : new[] { original });
            var file = candidates.Where(path => Path.GetFullPath(path).StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                .Select(path => new FileInfo(path)).Where(info => info.Exists).OrderByDescending(info => info.LastWriteTimeUtc).FirstOrDefault();
            return file?.FullName ?? "";
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException) { return ""; }
    }
    public static string Read(string home, string id, string original) {
        try {
            var path = LatestPath(home, id, original, requireAlias: true);
            if (path.Length == 0) return "";
            var file = new FileInfo(path);
            lock (gate) if (texts.TryGetValue(file.FullName, out var cached) && cached.Length == file.Length && cached.Modified == file.LastWriteTimeUtc.Ticks) return cached.Text;
            var text = ReadTail(file.FullName);
            lock (gate) {
                if (texts.Count > 512) texts.Clear();
                texts[file.FullName] = (file.Length, file.LastWriteTimeUtc.Ticks, text);
            }
            return text;
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return ""; }
    }

    private static string ReadTail(string path) {
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        for (long amount = 1024 * 1024; amount <= 16 * 1024 * 1024; amount *= 2) {
            var start = Math.Max(0, stream.Length - amount);
            stream.Seek(start, SeekOrigin.Begin);
            using var reader = new StreamReader(stream, leaveOpen: true);
            if (start > 0) reader.ReadLine(); // The first line may start in the middle of JSON/UTF-8.
            var latest = "";
            while (reader.ReadLine() is { } line) {
                try {
                    var record = JsonNode.Parse(line);
                    if (record?["type"]?.ToString() != "response_item" || record["payload"] is not JsonObject payload || payload["role"]?.ToString() != "user") continue;
                    var parts = payload["content"] as JsonArray ?? new();
                    var content = LatestSessionPreview.ContentText(parts);
                    var candidate = LatestSessionPreview.Text(content);
                    if (candidate.Length > 0) latest = candidate;
                } catch (JsonException) { }
            }
            if (latest.Length > 0 || start == 0) return latest;
        }
        return "";
    }
}
