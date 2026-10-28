using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

/** A transient read-only follower. No resume, writer ownership, or operation dispatch. */
internal static class CodexDesktopActivity
{
    private sealed record Entry(long At, Task<JsonObject> Pending);
    private static readonly object gate = new();
    private static readonly Dictionary<string, Entry> cache = new();
    public static async Task<JsonObject> Read(string home, string session, CancellationToken ct) {
        Task<JsonObject> task;
        lock (gate) {
            var now = Environment.TickCount64;
            var key = Path.GetFullPath(home) + "\n" + session;
            if (!cache.TryGetValue(key, out var entry) || now - entry.At > 1500 && entry.Pending.IsCompleted) {
                foreach (var expired in cache.Where(pair => now - pair.Value.At > 10000 && pair.Value.Pending.IsCompleted).Select(pair => pair.Key).ToArray()) cache.Remove(expired);
                if (cache.Count >= 16) return Obj(("available", false));
                cache[key] = entry = new(now, Capture(home, session));
            }
            task = entry.Pending;
        }
        var result = (await task.WaitAsync(ct)).DeepClone().AsObject();
        result["session_id"] = session;
        return result;
    }
    private static async Task<JsonObject> Capture(string home, string session) {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        try {
            await foreach (var snapshot in new CodexDesktopSync(home).Follow(session, timeout.Token)) return Project(snapshot);
        } catch (Exception error) when (error is IOException or OperationCanceledException or UnauthorizedAccessException or InvalidOperationException or ArgumentException or System.ComponentModel.Win32Exception or System.Text.Json.JsonException or CodexError) { }
        return Obj(("available", false));
    }
    internal static void Merge(JsonObject activity, JsonObject desktop) {
        if (!desktop.B("available")) return;
        var current = activity.S("activity_id");
        var finished = current.Length > 0 && desktop["turn_statuses"].S(current) is "completed" or "failed" or "interrupted";
        // Desktop can finish the same turn; its idle view says nothing about another client's turn.
        if (!activity.B("running") || desktop.B("running") || finished) {
            if (desktop.S("activity_id") != current) {
                activity["progress"] = new JsonArray(); activity["event_count"] = 0; activity["last_response_at"] = null;
            }
            foreach (var field in new[] { "available", "running", "activity_id", "kind", "started_at", "phase_started_at" }) activity[field] = desktop[field]?.DeepClone();
        }
        activity["observed_only"] = true; activity["source"] = "desktop";
        if (!activity.B("running")) { activity["progress"] = new JsonArray(); activity["event_count"] = 0; }
    }
    internal static JsonObject Project(JsonObject snapshot) {
        var turns = snapshot.A("turns").ToList();
        if (snapshot["turnHistory"]?["history"]?["entitiesByKey"] is JsonObject entities) turns.AddRange(entities.Select(pair => pair.Value));
        // History entities may lack turnStartedAtMs; an inProgress turn is always the
        // live one under the single-writer desktop, so prioritize it over timestamps.
        var turn = turns.Where(t => t != null && t.S("turnId").Length > 0)
            .GroupBy(t => t.S("turnId")).Select(group => group.First())
            .OrderBy(t => t.S("status") == "inProgress" ? 1 : 0)
            .ThenBy(t => t.L("turnStartedAtMs")).LastOrDefault();
        // An incomplete desktop snapshot is not proof of idle state.
        if (turn == null) return Obj(("available", false));
        var status = turn.S("status");
        if (status is not ("inProgress" or "completed" or "failed" or "interrupted")) return Obj(("available", false));
        var items = turn.A("items");
        var compaction = items.LastOrDefault(item => item.S("type") == "contextCompaction");
        var compacting = status == "inProgress" && compaction != null && !compaction.B("completed");
        var onlyCompaction = compaction != null && items.All(item => item.S("type") is "contextCompaction" or "error");
        var start = turn.L("turnStartedAtMs");
        // Automatic compaction can follow a long task. Do not reuse that task's clock.
        // Its item lacks a native start time, so omit the clock rather than invent one.
        var phaseStart = onlyCompaction ? start : compacting ? 0 : start;
        var statuses = new JsonObject();
        foreach (var t in turns.Where(t => t != null && t.S("turnId").Length > 0).OrderBy(t => t.L("turnStartedAtMs")).TakeLast(64)) statuses[t.S("turnId")] = t.S("status");
        return Obj(("available", true), ("running", status == "inProgress"), ("status", status),
            ("activity_id", turn.S("turnId")), ("kind", compacting || onlyCompaction ? "compact" : "task"),
            ("turn_statuses", statuses),
            ("started_at", start > 0 ? (object)start : null), ("phase_started_at", phaseStart > 0 ? (object)phaseStart : null));
    }
}
