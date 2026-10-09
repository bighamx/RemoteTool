using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Configuration;
using SQLite;

namespace RemoteTool.WebApi.Services;

/// <summary>Read-only observation of turns owned by CLI, TUI, gateway or another API client.</summary>
public sealed class HermesSessionActivity(IConfiguration configuration)
{
    public string DatabasePath {
        get {
            var home = configuration["Hermes:HomeDirectory"] ?? RemoteToolPaths.HermesHome;
            return Path.Combine(home, "state.db");
        }
    }
    public JsonObject Read(string id) => ReadDatabase(DatabasePath, new[] { id }, true).GetValueOrDefault(id) ?? Unknown(id);
    public IReadOnlyDictionary<string, JsonObject> ReadMany(IEnumerable<string> ids) => ReadDatabase(DatabasePath, ids, false);
    public IReadOnlyList<string> ActiveRunCandidates(string id) => ReadActiveRunCandidates(Path.Combine(Path.GetDirectoryName(DatabasePath)!, "runs_idempotency.db"), id);

    // Desktop session streams also publish native run IDs here, without going through
    // RemoteTool's run registry. Candidates must still be authenticated by GET /v1/runs/{id}.
    internal static IReadOnlyList<string> ReadActiveRunCandidates(string database, string id) {
        if (!Regex.IsMatch(id ?? "", "^[a-zA-Z0-9_-]{1,160}$") || !File.Exists(database)) return Array.Empty<string>();
        try {
            SQLitePCL.Batteries_V2.Init();
            using var db = new SQLiteConnection(database, SQLiteOpenFlags.ReadOnly | SQLiteOpenFlags.FullMutex);
            db.BusyTimeout = TimeSpan.FromMilliseconds(250);
            return db.Query<NativeRunRow>("SELECT run_id FROM run_idempotency WHERE " +
                "CASE WHEN json_valid(status_json) THEN json_extract(status_json,'$.session_id') END=? AND " +
                "CASE WHEN json_valid(status_json) THEN json_extract(status_json,'$.status') END " +
                "IN ('started','submitting','queued','running','stopping','waiting_for_approval') ORDER BY updated_at DESC LIMIT 3", id)
                .Select(row => row.Id).Where(run => Regex.IsMatch(run ?? "", "^[a-zA-Z0-9_-]{1,160}$")).Distinct().ToArray();
        } catch (Exception error) when (error is SQLiteException or IOException or UnauthorizedAccessException or DllNotFoundException or TypeInitializationException) {
            return Array.Empty<string>();
        }
    }
    private sealed class NativeRunRow { [Column("run_id")] public string Id { get; set; } }

    private sealed class SessionRow {
        [Column("id")] public string Id { get; set; }
        [Column("parent_session_id")] public string Parent { get; set; }
        [Column("source")] public string Source { get; set; }
        [Column("end_reason")] public string EndReason { get; set; }
        [Column("model_config")] public string Config { get; set; }
        [Column("last_activity_description")] public string Description { get; set; }
    }
    private sealed class LeaseRow {
        [Column("holder")] public string Holder { get; set; }
        [Column("acquired_at")] public double Started { get; set; }
        [Column("expires_at")] public double Expires { get; set; }
    }
    private sealed class MessageRow {
        [Column("id")] public long Id { get; set; }
        [Column("role")] public string Role { get; set; }
        [Column("timestamp")] public double Timestamp { get; set; }
        [Column("tool_call_id")] public string CallId { get; set; }
        [Column("tool_calls")] public string Calls { get; set; }
        [Column("failed")] public bool Failed { get; set; }
    }
    private static JsonObject Unknown(string id) => new() { ["session_id"] = id, ["available"] = false, ["running"] = false };
    private static bool IndependentChild(SessionRow row) {
        if (row.Source is "branch" or "fork" or "delegate" or "session_reset") return true;
        try {
            if (JsonNode.Parse(row.Config ?? "{}") is JsonObject config)
                return new[] { "_branched_from", "_delegate_from", "_reset_from" }.Any(key => config[key]?.ToString() == row.Parent);
        } catch (JsonException) { }
        return false;
    }
    private static bool? HolderAlive(string holder, double started) {
        var match = Regex.Match(holder ?? "", @"(?:^|:)pid=(\d+)(?::|$)");
        if (!match.Success || !int.TryParse(match.Groups[1].Value, out var pid)) return null;
        if (OperatingSystem.IsLinux()) {
            try {
                var remote = Regex.Match(holder ?? "", @"(?:^|:)pidns=(\d+)(?::|$)");
                var local = Regex.Match(new FileInfo("/proc/self/ns/pid").LinkTarget ?? "", @"\[(\d+)\]");
                if (!remote.Success || !local.Success || remote.Groups[1].Value != local.Groups[1].Value) return null;
            } catch (IOException) { return null; }
        }
        try {
            using var process = Process.GetProcessById(pid);
            // A recycled PID is not the owner of the old lease.
            return !process.HasExited && new DateTimeOffset(process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds() / 1000.0 <= started + 2;
        } catch (System.ComponentModel.Win32Exception) { return null; }
        catch (Exception error) when (error is ArgumentException or InvalidOperationException) { return false; }
    }
    internal static IReadOnlyDictionary<string, JsonObject> ReadDatabase(string database, IEnumerable<string> ids, bool tools,
        double? clock = null, Func<string, double, bool> holderAlive = null) {
        var targets = ids.Where(id => Regex.IsMatch(id ?? "", "^[a-zA-Z0-9_-]{1,160}$")).Distinct().Take(100).ToArray();
        var output = targets.ToDictionary(id => id, Unknown);
        if (!File.Exists(database) || targets.Length == 0) return output;
        try {
            SQLitePCL.Batteries_V2.Init();
            using var db = new SQLiteConnection(database, SQLiteOpenFlags.ReadOnly | SQLiteOpenFlags.FullMutex);
            db.BusyTimeout = TimeSpan.FromMilliseconds(250);
            var now = clock ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() / 1000.0;
            foreach (var id in targets) {
                const string fields = "id,parent_session_id,source,end_reason,model_config,last_activity_description";
                SessionRow Get(string key) => db.Query<SessionRow>($"SELECT {fields} FROM sessions WHERE id=?", key).FirstOrDefault();
                var requested = Get(id); if (requested == null) { output[id]["available"] = true; continue; }
                var root = requested;
                var seen = new HashSet<string> { root.Id };
                for (var depth = 0; depth < 32 && !string.IsNullOrEmpty(root.Parent) && !IndependentChild(root); depth++) {
                    var parent = Get(root.Parent);
                    if (parent == null || parent.EndReason != "compression" || !seen.Add(parent.Id)) break;
                    root = parent;
                }
                var lineage = new List<SessionRow> { root };
                seen = new HashSet<string> { root.Id };
                while (lineage.Count < 32 && lineage[^1].EndReason == "compression") {
                    var next = db.Query<SessionRow>($"SELECT {fields} FROM sessions WHERE parent_session_id=? ORDER BY started_at DESC", lineage[^1].Id)
                        .FirstOrDefault(row => !IndependentChild(row) && !seen.Contains(row.Id));
                    if (next == null) break;
                    seen.Add(next.Id); lineage.Add(next);
                }
                var lease = db.Query<LeaseRow>("SELECT holder,acquired_at,expires_at FROM session_turn_leases WHERE conversation_id=?", root.Id).FirstOrDefault();
                var validLease = lease != null && lease.Started > 0 && lease.Started <= now && lease.Expires > now;
                bool? alive = validLease ? (holderAlive != null ? holderAlive(lease.Holder, lease.Started) : HolderAlive(lease.Holder, lease.Started)) : false;
                var running = validLease && alive == true;
                var placeholders = string.Join(',', lineage.Select(_ => "?"));
                var args = lineage.Select(row => (object)row.Id).ToArray();
                var revision = db.ExecuteScalar<long>($"SELECT COALESCE(MAX(id),0) FROM messages WHERE active=1 AND session_id IN ({placeholders})", args);
                var result = output[id] = new JsonObject { ["session_id"] = id, ["available"] = alive.HasValue, ["running"] = running,
                    ["observed_only"] = true, ["source"] = lineage[^1].Source, ["revision"] = revision, ["progress"] = new JsonArray(), ["event_count"] = 0 };
                if (!running) continue;
                var identity = root.Id + "\n" + lease.Holder + "\n" + lease.Started.ToString("R", System.Globalization.CultureInfo.InvariantCulture);
                result["activity_id"] = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity))).ToLowerInvariant();
                result["started_at"] = (long)(lease.Started * 1000);
                result["activity_text"] = lineage[^1].Description;
                var turnArgs = args.Concat(new object[] { lease.Started }).ToArray();
                var where = $"active=1 AND session_id IN ({placeholders}) AND timestamp>=?";
                var response = db.ExecuteScalar<double>($"SELECT COALESCE(MAX(timestamp),0) FROM messages WHERE {where} AND role IN ('assistant','tool')", turnArgs);
                result["last_response_at"] = response > 0 ? JsonValue.Create((long)(response * 1000)) : null;
                if (!tools) continue;
                result["event_count"] = db.ExecuteScalar<int>($"SELECT COALESCE(SUM(CASE WHEN json_valid(tool_calls) AND json_type(tool_calls)='array' THEN json_array_length(tool_calls) ELSE 0 END),0) FROM messages WHERE {where} AND role='assistant'", turnArgs);
                var rows = db.Query<MessageRow>($"SELECT id,role,timestamp,tool_call_id,CASE WHEN length(tool_calls)<=65536 THEN tool_calls ELSE NULL END AS tool_calls," +
                    $"CASE WHEN role='tool' AND (finish_reason='error' OR (json_valid(content) AND json_extract(content,'$.error') IS NOT NULL)) THEN 1 ELSE 0 END AS failed " +
                    $"FROM messages WHERE {where} AND role IN ('assistant','tool') ORDER BY id DESC LIMIT 512", turnArgs);
                var progress = new List<JsonObject>();
                foreach (var row in rows.AsEnumerable().Reverse()) {
                    if (row.Role != "assistant" || string.IsNullOrWhiteSpace(row.Calls)) continue;
                    try {
                        if (JsonNode.Parse(row.Calls) is not JsonArray calls) continue;
                        var index = 0;
                        foreach (var call in calls.OfType<JsonObject>()) {
                            var callId = call["id"]?.ToString();
                            if (string.IsNullOrWhiteSpace(callId) || call["function"] is not JsonObject function) continue;
                            var completed = rows.FirstOrDefault(item => item.Role == "tool" && item.CallId == callId && item.Id > row.Id);
                            var preview = function?["arguments"]?.ToString() ?? "";
                            try {
                                var parsed = JsonNode.Parse(preview);
                                preview = parsed?["command"]?.ToString() ?? parsed?["path"]?.ToString() ?? preview;
                            } catch (Exception error) when (error is JsonException or InvalidOperationException) { }
                            preview = Regex.Replace(preview, @"\s+", " ").Trim(); if (preview.Length > 160) preview = preview[..160] + "…";
                            progress.Add(new JsonObject { ["id"] = $"{row.Id}:{index++}:{callId}", ["tool"] = function?["name"]?.ToString() ?? "工具",
                                ["preview"] = preview, ["status"] = completed == null ? "running" : completed.Failed ? "failed" : "completed",
                                ["timestamp"] = (long)((completed?.Timestamp ?? row.Timestamp) * 1000) });
                        }
                    } catch (JsonException) { }
                }
                result["progress"] = new JsonArray(progress.TakeLast(30).Select(row => (JsonNode)row).ToArray());
            }
        } catch (Exception error) when (error is SQLiteException or IOException or UnauthorizedAccessException or DllNotFoundException or TypeInitializationException) {
            return targets.ToDictionary(id => id, Unknown);
        }
        return output;
    }
}
