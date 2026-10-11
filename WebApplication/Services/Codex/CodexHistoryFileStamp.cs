namespace RemoteTool.WebApi.Services.Codex;

internal readonly record struct CodexHistoryFileStamp(string Path, long Length, long Modified) {
    public static CodexHistoryFileStamp? Capture(string path) {
        try {
            if (string.IsNullOrWhiteSpace(path)) return null;
            var file = new FileInfo(path);
            return file.Exists ? new(file.FullName, file.Length, file.LastWriteTimeUtc.Ticks) : null;
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException or ArgumentException) { return null; }
    }
    public bool SameVersion(CodexHistoryFileStamp? other) => other is { } current &&
        string.Equals(Path, current.Path, StringComparison.OrdinalIgnoreCase) &&
        Length == current.Length && Modified == current.Modified;
}
