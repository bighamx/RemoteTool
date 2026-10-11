using RemoteTool.WebApi.Services.Codex;

internal static class CodexEventReplayChecks {
    public static void Run() {
        var gap = CodexEventReplayGap.Marker(1, 45);
        if (gap?["event"]?.ToString() != "stream.gap" || gap["seq"]?.GetValue<long>() != 44 ||
            gap["from_seq"]?.GetValue<long>() != 2 || gap["to_seq"]?.GetValue<long>() != 44)
            throw new Exception("Expired SSE replay must announce the missing range before replaying retained events");
        if (CodexEventReplayGap.Marker(44, 45) != null || CodexEventReplayGap.Marker(45, 45) != null)
            throw new Exception("Contiguous SSE replay must not announce a gap");
        Console.WriteLine("Codex SSE replay gap checks passed");
    }
}
