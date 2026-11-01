using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.Storage;

internal static class StorageFiles
{
    internal static readonly StringComparison Comparison = OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
    public static bool Safe(string root, string path) {
        root = Path.GetFullPath(root); path = Path.GetFullPath(path);
        if (!path.StartsWith(root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar, Comparison)) return false;
        for (var current = path; current != null; current = Path.GetDirectoryName(current)) {
            try { if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) return false; }
            catch (FileNotFoundException) { }
            catch (DirectoryNotFoundException) { }
            catch (UnauthorizedAccessException) { return false; }
            if (current.Equals(root, Comparison)) return true;
        }
        return false;
    }
    public static IEnumerable<string> Walk(string root, CancellationToken ct) {
        if (!Directory.Exists(root) || (File.GetAttributes(root) & FileAttributes.ReparsePoint) != 0) yield break;
        var pending = new Stack<string>(); pending.Push(root);
        while (pending.Count > 0) {
            ct.ThrowIfCancellationRequested();
            var folder = pending.Pop(); string[] entries;
            try { entries = Directory.GetFileSystemEntries(folder); } catch (IOException) { continue; } catch (UnauthorizedAccessException) { continue; }
            foreach (var entry in entries) {
                FileAttributes flags; try { flags = File.GetAttributes(entry); } catch (IOException) { continue; } catch (UnauthorizedAccessException) { continue; }
                if ((flags & FileAttributes.ReparsePoint) != 0) continue;
                if ((flags & FileAttributes.Directory) != 0) pending.Push(entry); else yield return entry;
            }
        }
    }
    public static string Kind(string name) => Path.GetExtension(name).ToLowerInvariant() switch {
        ".png" or ".jpg" or ".jpeg" or ".webp" or ".gif" or ".bmp" or ".heic" or ".avif" => "image",
        ".mp4" or ".mkv" or ".mov" or ".webm" or ".avi" or ".m4v" => "video",
        ".mp3" or ".m4a" or ".wav" or ".flac" or ".opus" or ".ogg" => "audio",
        ".pdf" or ".md" or ".txt" or ".doc" or ".docx" or ".xlsx" or ".pptx" or ".json" or ".csv" => "document",
        ".apk" => "package", ".log" => "log", _ => "other"
    };
    public static void WriteJson<T>(string path, T value) {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temp = path + ".tmp-" + Guid.NewGuid().ToString("N");
        try {
            using (var output = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None)) {
                JsonSerializer.Serialize(output, value); output.Flush(true);
            }
            File.Move(temp, path, true);
        } finally { if (File.Exists(temp)) File.Delete(temp); }
    }
}

/** Explicitly removed project-file snapshots must not be silently imported again on history refresh. */
public static class AttachmentDeletionLedger
{
    private static readonly object Gate = new();
    private sealed record Cached(long Ticks, long Bytes, HashSet<string> Entries);
    private static readonly Dictionary<string, Cached> Cache = new();
    private static string Ledger(string root) => Path.Combine(root, ".chuckie-storage-deletions.json");
    private static HashSet<string> Read(string root) {
        var path = Ledger(root); var info = new FileInfo(path);
        if (!info.Exists) return new(StringComparer.Ordinal);
        if (!StorageFiles.Safe(root, path)) throw new IOException("附件删除索引不能为链接");
        if (Cache.TryGetValue(root, out var cached) && cached.Ticks == info.LastWriteTimeUtc.Ticks && cached.Bytes == info.Length) return cached.Entries;
        var rows = JsonSerializer.Deserialize<HashSet<string>>(File.ReadAllText(path)) ?? new();
        Cache[root] = new(info.LastWriteTimeUtc.Ticks, info.Length, rows); return rows;
    }
    public static bool IsSuppressed(string root, string path) {
        if (File.Exists(path)) return false;
        lock (Gate) return Read(root).Contains(Path.GetRelativePath(root, path).Replace('\\', '/'));
    }
    public static void Set(string root, string path, bool deleted) {
        if (!StorageFiles.Safe(root, path)) throw new IOException("附件路径已改变");
        lock (Gate) {
            var entries = new HashSet<string>(Read(root), StringComparer.Ordinal);
            var relative = Path.GetRelativePath(root, path).Replace('\\', '/');
            if (deleted) entries.Add(relative); else entries.Remove(relative);
            StorageFiles.WriteJson(Ledger(root), entries); Cache.Remove(root);
        }
    }
}
