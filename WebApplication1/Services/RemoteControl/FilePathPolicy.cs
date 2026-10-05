namespace ChuckieHelper.WebApi.Services.RemoteControl;

internal static class FilePathPolicy
{
    private static StringComparison Comparison => OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal;
    public static bool IsWithin(string root, string path, bool includeRoot = true) {
        var parent = Path.TrimEndingDirectorySeparator(Path.GetFullPath(root));
        var candidate = Path.TrimEndingDirectorySeparator(Path.GetFullPath(path));
        return (includeRoot && candidate.Equals(parent, Comparison)) ||
            candidate.StartsWith(Path.EndsInDirectorySeparator(parent) ? parent : parent + Path.DirectorySeparatorChar, Comparison);
    }
    public static string Resolve(string root, string? path) {
        if (string.IsNullOrWhiteSpace(path)) throw new ArgumentException("文件路径不能为空");
        if (Path.IsPathRooted(path)) {
            if (!Path.IsPathFullyQualified(path)) throw new ArgumentException("请提供完整绝对路径");
            return Path.GetFullPath(path);
        }
        var result = Path.GetFullPath(Path.Combine(root, path));
        if (!IsWithin(root, result)) throw new ArgumentException("相对路径不能离开用户目录，请使用完整绝对路径");
        return result;
    }
    public static void ValidateCopy(string source, string destination) {
        if (IsWithin(source, destination)) throw new IOException("不能把文件夹复制到自身或其子目录");
        // Never follow junctions while recursively copying: they can lead back to an ancestor.
        if ((File.GetAttributes(source) & FileAttributes.ReparsePoint) != 0) throw new IOException("递归复制暂不支持目录链接，请选择实际目录");
        EnsureNoDirectoryLinks(destination);
    }
    public static void EnsureNoDirectoryLinks(string destination) {
        for (var parent = new DirectoryInfo(Path.GetFullPath(destination)); parent != null; parent = parent.Parent)
            if (parent.Exists && (parent.Attributes & FileAttributes.ReparsePoint) != 0) throw new IOException("复制目标不能经过目录链接，请选择实际目录");
    }
}
