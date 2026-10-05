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
        if (string.IsNullOrWhiteSpace(path) || !OperatingSystem.IsWindows()) return false;
        try { using var probe = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.Read); return false; }
        catch (IOException error) when ((error.HResult & 0xffff) is 32 or 33) { return CodexRolloutSnapshot.Read(home, path).B("running"); }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
    }
    public static string LastModel(string home, string path) => LastSettings(home, path).S("model");
    public static JsonObject LastSettings(string home, string path) => CodexRolloutSnapshot.Read(home, path)["settings"]!.AsObject();
}
