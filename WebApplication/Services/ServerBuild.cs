using System.Reflection;

namespace RemoteTool.WebApi.Services;

/// <summary>Immutable identifier embedded in the server binary, shared by API and Web UI.</summary>
public static class ServerBuild
{
    /// <summary>Public App/Web API contract. Increment for major protocol changes, not routine builds.</summary>
    public const int ProtocolVersion = 1;
    public static string DisplayVersion => $"协议 v{ProtocolVersion} · 编译 {Version}";

    public static string Version { get; } = typeof(ServerBuild).Assembly
        .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "Unknown";
}
