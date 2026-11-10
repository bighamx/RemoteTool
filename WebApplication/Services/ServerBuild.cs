using System.Reflection;

namespace RemoteTool.WebApi.Services;

/// <summary>Immutable identifier embedded in the server binary, shared by API and Web UI.</summary>
public static class ServerBuild
{
    /// <summary>Public App/Web API contract. Increment for major protocol changes, not routine builds.</summary>
    public const int ProtocolVersion = 1;
    public static string DisplayVersion => $"协议 v{ProtocolVersion} · 版本 {Version}";

    // 注意：静态字段按文本顺序初始化，Version 必须先读方法而不是 ReleaseSequence 属性，
    // 否则此时属性还是默认值 0，发行包会被当成开发构建显示 hash。
    public static string Version { get; } = Describe(typeof(ServerBuild).Assembly
        .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "unknown", ReadReleaseSequence());

    /// <summary>正式发行包（带 ReleaseSequence 编译）显示语义版本号，开发构建显示提交短 hash。</summary>
    internal static string Describe(string value, long sequence) {
        var semantic = value.Split('+')[0];
        return sequence > 0 && semantic.Count(character => character == '.') >= 1 ? semantic : ShortVersion(value);
    }

    public static long ReleaseSequence => ReadReleaseSequence();

    /// <summary>Cache key for lazily loaded scripts/styles: stable until the deployed web assets change.</summary>
    public static string AssetVersion { get; set; } = Version;

    internal static string ComputeAssetVersion(string? webRoot) {
        if (string.IsNullOrEmpty(webRoot) || !Directory.Exists(webRoot)) return Version;
        var hash = new System.Text.StringBuilder(Version);
        foreach (var file in Directory.EnumerateFiles(webRoot, "*", SearchOption.AllDirectories).Order(StringComparer.Ordinal)) {
            var info = new FileInfo(file);
            hash.Append('|').Append(Path.GetRelativePath(webRoot, file)).Append(':').Append(info.Length).Append(':').Append(info.LastWriteTimeUtc.Ticks);
        }
        return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(hash.ToString())))[..16].ToLowerInvariant();
    }

    private static long ReadReleaseSequence() => long.TryParse(typeof(ServerBuild).Assembly
        .GetCustomAttributes<AssemblyMetadataAttribute>().FirstOrDefault(a => a.Key == "ReleaseSequence")?.Value, out var value) ? value : 0;

    internal static string ShortVersion(string value) {
        var revision = value.Split('+').Last();
        var hash = revision.Split('-')[0];
        return hash.Length >= 12 && hash.All(Uri.IsHexDigit)
            ? hash[..12] + (revision.EndsWith("-dirty", StringComparison.Ordinal) ? "-dirty" : "")
            : "unknown";
    }
}
