using System.Text.Json;
using System.Text.Json.Nodes;

namespace ChuckieHelper.WebApi.Services.Codex;

internal static class CodexRollout
{
    public static string CompactResult(string home, string path, long offset) {
        if (!Path.GetFullPath(path).StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException("Invalid rollout path");
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        stream.Seek(offset, SeekOrigin.Begin);
        using var reader = new StreamReader(stream);
        while (reader.ReadLine() is { } line) {
            try {
                var record = JsonNode.Parse(line);
                if (record?["type"]?.ToString() == "compacted") return "completed";
                if (record?["type"]?.ToString() == "event_msg") {
                    var kind = record["payload"]?["type"]?.ToString();
                    if (kind == "turn_aborted") return "interrupted";
                    if (kind == "error") return "failed";
                }
            } catch (JsonException) { }
        }
        return "";
    }
    private static readonly object activityGate = new();
    private static readonly Dictionary<string, (long Length, long Modified, bool Running)> activity = new(StringComparer.OrdinalIgnoreCase);
    public static bool IsRunning(string home, string path) {
        if (string.IsNullOrWhiteSpace(path)) return false;
        try {
            if (!Path.GetFullPath(path).StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return false;
            var info = new FileInfo(path); if (!info.Exists) return false;
            bool running;
            lock (activityGate) {
                if (activity.TryGetValue(path, out var cached) && cached.Length == info.Length && cached.Modified == info.LastWriteTimeUtc.Ticks) running = cached.Running;
                else {
                    running = false;
                    using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
                    using var reader = new StreamReader(stream);
                    while (reader.ReadLine() is { } line) {
                        try {
                            if (JsonNode.Parse(line) is JsonObject record && record["type"]?.ToString() == "event_msg" && record["payload"] is JsonObject payload) {
                                var kind = payload["type"]?.ToString();
                                if (kind == "task_started") running = true;
                                else if (kind is "task_complete" or "turn_aborted") running = false;
                            }
                        } catch (JsonException) { }
                    }
                    if (activity.Count >= 512) activity.Clear();
                    activity[path] = (info.Length, info.LastWriteTimeUtc.Ticks, running);
                }
            }
            if (!running || !OperatingSystem.IsWindows()) return false;
            try { using var probe = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read); return false; }
            catch (IOException error) when ((error.HResult & 0xffff) is 32 or 33) { return true; }
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
    }
    public static string LastModel(string home, string path) {
        var model = "";
        if (string.IsNullOrWhiteSpace(path)) return model;
        try {
            if (!Path.GetFullPath(path).StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return model;
            // Desktop Codex keeps the active rollout open for writing. Reading must
            // share writes and renames; a partial last JSON line is expected.
            using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
            using var reader = new StreamReader(stream);
            while (reader.ReadLine() is { } line) {
                try {
                    if (JsonNode.Parse(line) is JsonObject record && record["type"]?.ToString() == "turn_context" &&
                        record["payload"] is JsonObject payload && payload["model"]?.ToString() is { Length: > 0 } value) model = value;
                } catch (JsonException) { }
            }
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException) {
            // Optional model metadata must not prevent opening the session.
        }
        return model;
    }
}
