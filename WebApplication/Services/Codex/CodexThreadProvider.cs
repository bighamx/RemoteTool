using SQLite;

namespace RemoteTool.WebApi.Services.Codex;

/// <summary>
/// codex 把会话归属（model_provider / model）记在 CODEX_HOME 下的 state_N.sqlite 的 threads 表里，
/// thread/resume 会拿它和请求里的 provider 比对；仅改 rollout 元数据不够，必须同时更新这条记录，
/// 否则跨 Provider 切换会话模型仍会报 “This chat uses X, but your current provider is Y”。
/// </summary>
internal static class CodexThreadProvider
{
    public static bool Rehome(string home, string threadId, string provider, string model = "") {
        if (threadId.Length == 0 || provider.Length == 0 || !Directory.Exists(home)) return false;
        foreach (var path in Candidates(home)) {
            try {
                using var db = new SQLiteConnection(path, SQLiteOpenFlags.ReadWrite | SQLiteOpenFlags.FullMutex);
                db.BusyTimeout = TimeSpan.FromSeconds(3);
                var changed = db.Execute("UPDATE threads SET model_provider=? WHERE id=?", provider, threadId);
                if (changed <= 0) continue;
                if (model.Length > 0) db.Execute("UPDATE threads SET model=? WHERE id=?", model, threadId);
                return true;
            } catch (Exception error) when (error is SQLiteException or IOException or UnauthorizedAccessException or DllNotFoundException or TypeInitializationException) { }
        }
        return false;
    }
    private static IEnumerable<string> Candidates(string home) =>
        Directory.GetFiles(home, "state_*.sqlite")
            .Select(path => (path, version: int.TryParse(Path.GetFileNameWithoutExtension(path)["state_".Length..], out var parsed) ? parsed : 0))
            .OrderByDescending(entry => entry.version).Select(entry => entry.path);
}
