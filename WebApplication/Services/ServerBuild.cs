using System.Reflection;

namespace RemoteTool.WebApi.Services;

/// <summary>Immutable identifier embedded in the server binary, shared by API and Web UI.</summary>
public static class ServerBuild
{
    /// <summary>Public App/Web API contract. Increment for major protocol changes, not routine builds.</summary>
    public const int ProtocolVersion = 1;
    public static string DisplayVersion => $"协议 v{ProtocolVersion} · 版本 {Version}";

    public static string Version { get; } = ShortVersion(typeof(ServerBuild).Assembly
        .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "unknown");

    internal static string ShortVersion(string value) {
        var revision = value.Split('+').Last();
        var hash = revision.Split('-')[0];
        return hash.Length >= 12 && hash.All(Uri.IsHexDigit)
            ? hash[..12] + (revision.EndsWith("-dirty", StringComparison.Ordinal) ? "-dirty" : "")
            : "unknown";
    }
}
