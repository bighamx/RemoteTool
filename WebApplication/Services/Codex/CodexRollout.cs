using System.Text.Json;
using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

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
    public static bool IsRunning(string home, string path) {
        return WriterLocked(home, path) && CodexRolloutSnapshot.Read(home, path).B("running");
    }
    public static bool WriterLocked(string home, string path) {
        if (string.IsNullOrWhiteSpace(path) || !OperatingSystem.IsWindows()) return false;
        if (!Path.GetFullPath(path).StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return false;
        var locks = Path.Combine(home, "thread-writer-locks");
        var id = System.Text.RegularExpressions.Regex.Match(Path.GetFileName(path), "[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").Value;
        var nativeLock = Directory.Exists(locks) && id.Length > 0;
        var target = nativeLock ? Path.Combine(locks, id + ".lock") : path;
        if (!File.Exists(target)) return false;
        try { using var probe = new FileStream(target, FileMode.Open, FileAccess.Read, nativeLock ? FileShare.None : FileShare.Read); return false; }
        catch (IOException error) when ((error.HResult & 0xffff) is 32 or 33) { return true; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
    }
    public static string LastModel(string home, string path) => LastSettings(home, path).S("model");
    public static JsonObject LastSettings(string home, string path) => CodexRolloutSnapshot.Read(home, path)["settings"]!.AsObject();
}
