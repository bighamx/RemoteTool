using RemoteTool.WebApi.Services.Codex;

internal static class CodexHistoryCacheChecks {
    public static void Run() {
        var path = Path.Combine(Path.GetTempPath(), "codex-history-version-" + Guid.NewGuid() + ".jsonl");
        try {
            File.WriteAllText(path, "old\n");
            var before = CodexHistoryFileStamp.Capture(path);
            if (before is not { } original) throw new Exception("History file version unavailable");
            File.AppendAllText(path, "new reply\n");
            if (original.SameVersion(CodexHistoryFileStamp.Capture(path)))
                throw new Exception("An older history payload must not be cached under the appended file version");
            var after = CodexHistoryFileStamp.Capture(path);
            if (after is not { } current || !current.SameVersion(CodexHistoryFileStamp.Capture(path)))
                throw new Exception("An unchanged history file should be cacheable");
            Console.WriteLine("Codex history cache version checks passed");
        } finally { if (File.Exists(path)) File.Delete(path); }
    }
}
