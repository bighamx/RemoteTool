using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

/** A transient read-only follower. No resume, writer ownership, or operation dispatch. */
internal static class CodexDesktopActivity
{
    private sealed record Entry(long At, Task<JsonObject> Pending);
    private static readonly object gate = new();
    private static readonly Dictionary<string, Entry> cache = new();
    public static async Task<JsonObject> Read(string session, CancellationToken ct) {
        Task<JsonObject> task;
        lock (gate) {
            var now = Environment.TickCount64;
            if (!cache.TryGetValue(session, out var entry) || now - entry.At > 1500 && entry.Pending.IsCompleted) {
                foreach (var key in cache.Where(pair => now - pair.Value.At > 10000 && pair.Value.Pending.IsCompleted).Select(pair => pair.Key).ToArray()) cache.Remove(key);
                if (cache.Count >= 16) return Obj(("available", false));
                cache[session] = entry = new(now, Capture(session));
            }
            task = entry.Pending;
        }
        return (await task.WaitAsync(ct)).DeepClone().AsObject();
    }
    private static async Task<JsonObject> Capture(string session) {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        try {
            await foreach (var snapshot in CodexDesktopSync.Follow(session, timeout.Token)) return Project(snapshot);
        } catch (Exception error) when (error is IOException or OperationCanceledException or UnauthorizedAccessException or InvalidOperationException or ArgumentException or System.ComponentModel.Win32Exception or System.Text.Json.JsonException or CodexError) { }
        return Obj(("available", false));
    }
    internal static JsonObject Project(JsonObject snapshot) {
        var turns = snapshot.A("turns").ToList();
        if (snapshot["turnHistory"]?["history"]?["entitiesByKey"] is JsonObject entities) turns.AddRange(entities.Select(pair => pair.Value));
        var turn = turns.Where(t => t != null && t.S("turnId").Length > 0)
            .GroupBy(t => t.S("turnId")).Select(group => group.First())
            .OrderBy(t => t.L("turnStartedAtMs")).LastOrDefault();
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
