namespace ChuckieHelper.WebApi.Services.RemoteControl;

public static class FfmpegExecutable
{
    // IIS does not inherit the interactive user's WinGet PATH. An explicit path also works for the desktop agent.
    public static string Path => Environment.GetEnvironmentVariable("CHUCKIEHELPER_FFMPEG_PATH")
        ?? (File.Exists(System.IO.Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe"))
            ? System.IO.Path.Combine(AppContext.BaseDirectory, "tools", "ffmpeg.exe") : "ffmpeg");
}
