using System.Reflection;

namespace RemoteTool.WebApi.Services;

/// <summary>Immutable identifier embedded in the server binary, shared by API and Web UI.</summary>
public static class ServerBuild
{
    public static string Version { get; } = typeof(ServerBuild).Assembly
        .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "Unknown";
}
