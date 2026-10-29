namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexCatalogMaintenance
{
    // Only probe directories created by OfficialCatalog belong to this cleanup.
    public static void Cleanup(string folder, DateTime now) {
        var root = new DirectoryInfo(Path.Combine(Path.GetFullPath(folder), "official-catalog"));
        try {
            if (!root.Exists || root.Attributes.HasFlag(FileAttributes.ReparsePoint)) return;
            foreach (var directory in root.EnumerateDirectories()) {
                try {
                    if (!Guid.TryParseExact(directory.Name, "N", out _) ||
                        directory.LastWriteTimeUtc > now.AddHours(-1) || !NoLinks(directory)) continue;
                    foreach (var file in directory.EnumerateFiles("*", SearchOption.AllDirectories))
                        file.Attributes &= ~FileAttributes.ReadOnly;
                    directory.Delete(true);
                } catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
            }
        } catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }
    }

    private static bool NoLinks(DirectoryInfo directory) {
        if (directory.Attributes.HasFlag(FileAttributes.ReparsePoint)) return false;
        foreach (var entry in directory.EnumerateFileSystemInfos()) {
            if (entry.Attributes.HasFlag(FileAttributes.ReparsePoint)) return false;
            if (entry is DirectoryInfo child && !NoLinks(child)) return false;
        }
        return true;
    }
}
