using SQLite;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexMessageTimes
{
    private sealed class ItemTime
    {
        [Column("item_id")] public string Id { get; set; }
        [Column("created_at_ms")] public long Timestamp { get; set; }
    }

    public static IReadOnlyDictionary<string, long> Read(string home, string thread)
    {
        var path = Path.Combine(home, "thread_history_1.sqlite");
        if (!File.Exists(path)) return new Dictionary<string, long>();
        try {
            SQLitePCL.Batteries_V2.Init();
            using var connection = new SQLiteConnection(path, SQLiteOpenFlags.ReadOnly | SQLiteOpenFlags.FullMutex);
            return connection.Query<ItemTime>("SELECT item_id, created_at_ms FROM thread_items WHERE thread_id = ?", thread)
                .Where(row => !string.IsNullOrWhiteSpace(row.Id) && row.Timestamp > 0)
                .GroupBy(row => row.Id).ToDictionary(group => group.Key, group => group.First().Timestamp);
        } catch (Exception error) when (error is SQLiteException or IOException or UnauthorizedAccessException or DllNotFoundException or TypeInitializationException) {
            // Older installations may not have this optional native history store.
            return new Dictionary<string, long>();
        }
    }
}
