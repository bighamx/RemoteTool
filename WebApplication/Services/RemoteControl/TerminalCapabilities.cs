namespace RemoteTool.WebApi.Services.RemoteControl;

public static class TerminalCapabilities
{
    public sealed record Choice(string Id, string Name);
    public static string Platform => OperatingSystem.IsWindows() ? "Windows" : OperatingSystem.IsMacOS() ? "macOS" : "Linux";
    public static string WindowsExecutable(string id) => id == "powershell"
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "WindowsPowerShell", "v1.0", "powershell.exe")
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System), "cmd.exe");
    public static string? UnixExecutable(string platform, Func<string,bool>? exists = null) {
        exists ??= File.Exists;
        return (platform == "macOS" ? new[]{"/bin/zsh","/bin/bash","/bin/sh"} : new[]{"/bin/bash","/bin/sh"}).FirstOrDefault(exists);
    }
    public static Choice[] Choices(string platform, Func<string,bool>? exists = null) {
        exists ??= File.Exists;
        if(platform != "Windows") return UnixExecutable(platform,exists) != null ? [new("shell","Shell")] : [];
        var rows = new List<Choice>();
        if(exists(WindowsExecutable("powershell"))) rows.Add(new("powershell","PowerShell"));
        if(exists(WindowsExecutable("cmd"))) rows.Add(new("cmd","CMD"));
        return rows.ToArray();
    }
    public static object Current() {
        var rows=Choices(Platform);
        return new { platform=Platform, types=rows, defaultType=rows.FirstOrDefault()?.Id ?? "" };
    }
    public static bool Supports(string id) => Choices(Platform).Any(c=>c.Id==id) || id=="shell" && OperatingSystem.IsWindows() && File.Exists(WindowsExecutable("cmd"));
}
