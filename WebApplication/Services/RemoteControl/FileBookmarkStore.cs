using System.Security.Claims;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.RemoteControl;

public sealed record FileBookmark(string Path, long CreatedAt);
public sealed record FileBookmarkSnapshot(IReadOnlyList<FileBookmark> Items, long Revision);

/// <summary>
/// Bookmarks belong to the authenticated RemoteTool identity, not to the cloud account.
/// Web and Android therefore share one list by connecting to the same RemoteTool server.
/// </summary>
public sealed class FileBookmarkStore
{
    private const int MaxBookmarks = 100;
    private const int MaxPathLength = 4096;
    private readonly object _gate = new();
    private readonly string _root;

    public FileBookmarkStore() : this(Path.Combine(RemoteToolPaths.ProgramData, "file-bookmarks")) { }

    public FileBookmarkStore(string root) => _root = Path.GetFullPath(root);

    public static string Identity(ClaimsPrincipal principal)
    {
        string Claim(string type) => principal.FindFirst(type)?.Value;
        var source = Claim("remotetool:auth-source") ?? "password";
        var provider = Claim("remotetool:auth-provider") ?? "local";
        var subject = Claim(ClaimTypes.NameIdentifier)
            ?? Claim("sub")
            ?? principal.Identity?.Name
            ?? throw new UnauthorizedAccessException("当前登录缺少稳定用户标识");
        return $"{source}:{provider}:{subject}";
    }

    public FileBookmarkSnapshot Read(ClaimsPrincipal principal)
    {
        lock (_gate)
        {
            var state = Load(Identity(principal));
            return new FileBookmarkSnapshot(state.Items, state.Revision);
        }
    }

    public FileBookmarkSnapshot Add(ClaimsPrincipal principal, string path)
    {
        var normalized = NormalizePath(path);
        lock (_gate)
        {
            var identity = Identity(principal);
            var state = Load(identity);
            if (!state.Items.Any(item => string.Equals(item.Path, normalized, PathComparison)))
            {
                if (state.Items.Length >= MaxBookmarks)
                    throw new InvalidOperationException($"书签最多保存 {MaxBookmarks} 条");
                state = new Persisted(state.Items.Concat([new FileBookmark(normalized,
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds())]).ToArray(), state.Revision + 1);
                Save(identity, state);
            }
            return new FileBookmarkSnapshot(state.Items, state.Revision);
        }
    }

    public FileBookmarkSnapshot Remove(ClaimsPrincipal principal, string path)
    {
        var normalized = NormalizePath(path);
        lock (_gate)
        {
            var identity = Identity(principal);
            var state = Load(identity);
            var items = state.Items.Where(item => !string.Equals(item.Path, normalized, PathComparison)).ToArray();
            if (items.Length != state.Items.Length)
            {
                state = new Persisted(items, state.Revision + 1);
                Save(identity, state);
            }
            return new FileBookmarkSnapshot(state.Items, state.Revision);
        }
    }

    private Persisted Load(string identity)
    {
        var path = FilePath(identity);
        if (!File.Exists(path)) return new Persisted([], 0);
        try
        {
            var state = JsonSerializer.Deserialize<Persisted>(File.ReadAllText(path))
                ?? throw new InvalidDataException("书签文件为空");
            return new Persisted(state.Items.Where(ValidPersisted)
                .DistinctBy(item => item.Path, PathComparer).ToArray(), Math.Max(0, state.Revision));
        }
        catch (JsonException error)
        {
            throw new InvalidDataException("书签文件格式损坏", error);
        }
    }

    private void Save(string identity, Persisted state)
    {
        var path = FilePath(identity);
        IdentityDataDirectory.Ensure(Path.GetDirectoryName(path)!);
        var temporary = path + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(state), new UTF8Encoding(false));
            IdentityDataDirectory.EnsureFile(temporary);
            File.Move(temporary, path, true);
            IdentityDataDirectory.EnsureFile(path);
        }
        finally
        {
            if (File.Exists(temporary)) File.Delete(temporary);
        }
    }

    private string FilePath(string identity)
    {
        var hash = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(identity))).ToLowerInvariant();
        return Path.Combine(_root, hash + ".json");
    }

    private static string NormalizePath(string path)
    {
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("书签路径不能为空");
        var value = path.Trim();
        if (value.Length > MaxPathLength) throw new ArgumentException("书签路径过长");
        if (value.IndexOfAny(['\0', '\r', '\n']) >= 0) throw new ArgumentException("书签路径包含无效字符");
        var full = Path.GetFullPath(value);
        // Trailing separators are presentation noise here; normalize them once so duplicate
        // bookmarks compare consistently and Windows drive roots keep their separator.
        var root = Path.GetPathRoot(full);
        return string.Equals(full, root, PathComparison) ? full : Path.TrimEndingDirectorySeparator(full);
    }

    private static bool ValidPersisted(FileBookmark item) =>
        item is not null && !string.IsNullOrWhiteSpace(item.Path) && item.Path.Length <= MaxPathLength;

    private static StringComparison PathComparison => OperatingSystem.IsWindows()
        ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
    private static StringComparer PathComparer => OperatingSystem.IsWindows()
        ? StringComparer.OrdinalIgnoreCase : StringComparer.Ordinal;
    private sealed record Persisted(FileBookmark[] Items, long Revision);
}
