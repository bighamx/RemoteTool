using System.Text.Json;

namespace RemoteTool.WebApi.Services.RemoteControl;

/// <summary>Docker Desktop installs per user, while IIS does not inherit that user's PATH.</summary>
internal static class DockerExecutable
{
    public static string Path => Resolve("REMOTETOOL_DOCKER_PATH", "DockerPath", "docker.exe", "docker");
    public static string ComposePath => Resolve("REMOTETOOL_DOCKER_COMPOSE_PATH", "DockerComposePath", "docker-compose.exe", "docker-compose");

    private static string Resolve(string environmentKey, string settingKey, string localName, string fallback)
    {
        var configured = Environment.GetEnvironmentVariable(environmentKey);
        if (string.IsNullOrWhiteSpace(configured))
        {
            var configFile = System.IO.Path.Combine(AppContext.BaseDirectory, "appsettings.json");
            if (File.Exists(configFile))
            {
                try
                {
                    using var config = JsonDocument.Parse(File.ReadAllText(configFile));
                    if (config.RootElement.TryGetProperty("RemoteControl", out var remote)
                        && remote.ValueKind == JsonValueKind.Object
                        && remote.TryGetProperty(settingKey, out var value)
                        && value.ValueKind == JsonValueKind.String)
                        configured = value.GetString();
                }
                catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException) { }
            }
        }
        if (!string.IsNullOrWhiteSpace(configured)) return Environment.ExpandEnvironmentVariables(configured.Trim());
        var local = System.IO.Path.Combine(RemoteToolPaths.ProgramData, "tools", localName);
        return File.Exists(local) ? local : fallback;
    }
}
