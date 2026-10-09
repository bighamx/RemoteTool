using System.Text.Json.Nodes;
using Microsoft.Extensions.Configuration;
using ChuckieHelper.WebApi.Services.Codex;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services;

public sealed class CodexSessionActivity(IConfiguration configuration)
{
    public JsonObject Read(string id) {
        var result = Obj(("session_id", id), ("available", false), ("running", false));
        try {
            var home = configuration["Codex:Home"];
            if (string.IsNullOrWhiteSpace(home)) {
                var connection = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "ChuckieHelper", "codex-bridge", "connection.json");
                home = File.Exists(connection) ? JsonNode.Parse(File.ReadAllText(connection)).S("home") : "";
            }
            if (string.IsNullOrWhiteSpace(home)) return result;
            var path = CodexPreviewRollout.LatestPath(home, id, "");
            if (path.Length == 0 && Directory.Exists(Path.Combine(home, "sessions")))
                path = Directory.EnumerateFiles(Path.Combine(home, "sessions"), "rollout-*-" + id + ".jsonl", new EnumerationOptions {
                    RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint, IgnoreInaccessible = true,
                }).OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault() ?? "";
            if (path.Length == 0) { result["available"] = true; return result; }
            var running = CodexRollout.IsRunning(home, path);
            var snapshot = CodexRolloutSnapshot.Read(home, path);
            running = running && snapshot.B("running");
            result["available"] = true; result["running"] = running; result["observed_only"] = true; result["source"] = "desktop";
            if (snapshot["activity"] is JsonObject activity) foreach (var pair in activity) result[pair.Key] = pair.Value?.DeepClone();
            if (!running) { result["progress"] = new JsonArray(); result["event_count"] = 0; }
            return result;
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Text.Json.JsonException or ArgumentException) { return result; }
    }
}
