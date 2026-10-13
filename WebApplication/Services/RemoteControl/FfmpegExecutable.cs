namespace RemoteTool.WebApi.Services.RemoteControl;

public static class FfmpegExecutable
{
    // Both IIS and the desktop agent use this resolver; neither can rely on the login user's PATH.
    public static string Path
    {
        get
        {
            var configured = Environment.GetEnvironmentVariable("REMOTETOOL_FFMPEG_PATH")
                ?? Environment.GetEnvironmentVariable("CHUCKIEHELPER_FFMPEG_PATH");
            if (string.IsNullOrWhiteSpace(configured))
            {
                var configFile = System.IO.Path.Combine(AppContext.BaseDirectory, "appsettings.json");
                if (File.Exists(configFile))
                {
                    try
                    {
                        using var config = System.Text.Json.JsonDocument.Parse(File.ReadAllText(configFile));
                        if (config.RootElement.TryGetProperty("RemoteControl", out var remote)
                            && remote.ValueKind == System.Text.Json.JsonValueKind.Object
                            && remote.TryGetProperty("FFmpegPath", out var path)
                            && path.ValueKind == System.Text.Json.JsonValueKind.String)
                            configured = path.GetString();
                    }
                    catch (Exception error) when (error is IOException or UnauthorizedAccessException or System.Text.Json.JsonException) { }
                }
            }
            if (!string.IsNullOrWhiteSpace(configured)) return Environment.ExpandEnvironmentVariables(configured.Trim());
            var bundled = System.IO.Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe");
            return File.Exists(bundled) ? bundled : "ffmpeg";
        }
    }
}
