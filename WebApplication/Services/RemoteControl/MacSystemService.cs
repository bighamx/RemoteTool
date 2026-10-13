using System.Diagnostics;
using System.Globalization;
using System.Net.NetworkInformation;
using System.Text.Json;
using System.Text.RegularExpressions;
using RemoteTool.WebApi.Models.RemoteControl;

namespace RemoteTool.WebApi.Services.RemoteControl;

/// <summary>macOS host operations. Desktop operations belong to the separately authorized helper.</summary>
public sealed class MacSystemService(MacDesktopBridge desktop) : ISystemControlService
{
    private static string Run(string executable, params string[] args) {
        var start = new ProcessStartInfo(executable) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var arg in args) start.ArgumentList.Add(arg);
        using var process = Process.Start(start)!;
        var output = process.StandardOutput.ReadToEndAsync(); var errors = process.StandardError.ReadToEndAsync();
        if (!process.WaitForExit(5000)) { process.Kill(true); throw new TimeoutException("系统命令超时"); }
        if (process.ExitCode != 0) throw new InvalidOperationException(errors.GetAwaiter().GetResult().Trim());
        return output.GetAwaiter().GetResult().Trim();
    }
    public void Shutdown() => Run("/sbin/shutdown", "-h", "now");
    public void Reboot() => Run("/sbin/shutdown", "-r", "now");
    public void CancelShutdown() => throw new PlatformNotSupportedException("macOS 第一版不支持取消关机");
    public void Sleep() => Run("/usr/bin/pmset", "sleepnow");
    public void Hibernate() => throw new PlatformNotSupportedException("macOS 不提供本项目的独立休眠操作");
    public void LockWorkstation() => Run("/System/Library/CoreServices/Menu Extras/User.menu/Contents/Resources/CGSession", "-suspend");
    public bool KillProcess(int id) { using var p = Process.GetProcessById(id); p.Kill(); return true; }
    public Task<List<ProcessInfo>> GetProcessListAsync() => Task.Run(() => {
        var rows = new List<ProcessInfo>();
        foreach (var line in Run("/bin/ps", "-axo", "pid=,pcpu=,rss=,comm=").Split('\n')) {
            var match = Regex.Match(line, @"^\s*(\d+)\s+([\d.]+)\s+(\d+)\s+(.+)$");
            if (!match.Success) continue;
            rows.Add(new ProcessInfo { Id = int.Parse(match.Groups[1].Value), CpuPercent = double.Parse(match.Groups[2].Value, CultureInfo.InvariantCulture),
                MemoryMB = long.Parse(match.Groups[3].Value) / 1024d, Name = Path.GetFileName(match.Groups[4].Value), FilePath = match.Groups[4].Value });
        }
        return rows;
    });
    public SystemInfo GetSystemInfo() {
        var assembly = typeof(MacSystemService).Assembly;
        var total = long.Parse(Run("/usr/sbin/sysctl", "-n", "hw.memsize"), CultureInfo.InvariantCulture);
        var vm = Run("/usr/bin/vm_stat");
        var page = Regex.Match(vm, @"page size of (\d+) bytes");
        long PageCount(string key) { var m = Regex.Match(vm, Regex.Escape(key) + @":\s*(\d+)"); return m.Success ? long.Parse(m.Groups[1].Value) : 0; }
        var available = (PageCount("Pages free") + PageCount("Pages inactive") + PageCount("Pages speculative")) * (page.Success ? long.Parse(page.Groups[1].Value) : 4096);
        var cpu = Regex.Match(Run("/usr/bin/top", "-l", "2", "-s", "1", "-n", "0"), @"CPU usage:\s*([\d.]+)% user,\s*([\d.]+)% sys", RegexOptions.RightToLeft);
        var info = new SystemInfo { Platform = "macOS", Version = assembly.GetName().Version?.ToString() ?? "", MachineName = Environment.MachineName,
            UserName = Environment.UserName, OSVersion = System.Runtime.InteropServices.RuntimeInformation.OSDescription, ProcessorCount = Environment.ProcessorCount,
            CpuLogicalProcessors = Environment.ProcessorCount, Is64Bit = Environment.Is64BitOperatingSystem, SystemDirectory = "/", CpuName = Run("/usr/sbin/sysctl", "-n", "machdep.cpu.brand_string"),
            TotalMemoryMB = total / 1048576, AvailableMemoryMB = available / 1048576, UsedMemoryMB = Math.Max(0, total - available) / 1048576,
            MemoryUsagePercent = (int)Math.Clamp(100d * (total - available) / total, 0, 100),
            CpuUsagePercent = cpu.Success ? (int)Math.Clamp(double.Parse(cpu.Groups[1].Value, CultureInfo.InvariantCulture) + double.Parse(cpu.Groups[2].Value, CultureInfo.InvariantCulture), 0, 100) : 0 };
        foreach (var drive in DriveInfo.GetDrives().Where(d => d.Name == "/" || d.Name.StartsWith("/Volumes/", StringComparison.Ordinal))) { try { if (drive.IsReady && drive.TotalSize > 0) info.Drives.Add(new() {
            Name = drive.Name, TotalGB = drive.TotalSize / 1073741824d, FreeGB = drive.AvailableFreeSpace / 1073741824d,
            UsedGB = (drive.TotalSize - drive.AvailableFreeSpace) / 1073741824d, DriveFormat = drive.DriveFormat,
            UsagePercent = (int)(100d * (drive.TotalSize - drive.AvailableFreeSpace) / drive.TotalSize) }); } catch (IOException) { } }
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces().Where(a => a.OperationalStatus == OperationalStatus.Up))
            info.NetworkAdapters.Add(new() { Name = adapter.Name, SpeedMbps = adapter.Speed / 1000000, MacAddress = adapter.GetPhysicalAddress().ToString() });
        return info;
    }
    public byte[] CaptureScreen() => Convert.FromBase64String(desktop.Call(new { type = "capture" }).GetProperty("jpeg").GetString()!);
    public async Task<byte[]> CaptureScreenAsync() => Convert.FromBase64String((await desktop.CallAsync(new { type = "capture" })).GetProperty("jpeg").GetString()!);
    public Task<JsonElement> NativeAsync(JsonElement request) => desktop.CallAsync(request);
    private void Mouse(string type, double x, double y, int button = 0, int delta = 0) => desktop.Call(new { type, x, y, button, delta });
    public void SendMouseClick(double x,double y) => Mouse("click",x,y);
    public void SendMouseRightClick(double x,double y) => Mouse("right-click",x,y);
    public void SendMouseMiddleClick(double x,double y) => Mouse("middle-click",x,y);
    public void SendMouseDown(double x,double y,int button=0) => Mouse("mouse-down",x,y,button);
    public void SendMouseUp(double x,double y,int button=0) => Mouse("mouse-up",x,y,button);
    public void SendMouseMove(double x,double y) => Mouse("mouse-move",x,y);
    public void SendMouseWheel(double x,double y,int delta) => Mouse("mouse-wheel",x,y,delta:delta);
    public void SendKeyboardEvent(byte vkCode,bool isKeyDown) => desktop.Call(new { type="keyboard",vkCode,isKeyDown });
    public void SendKeyboardEvents(byte[] codes,bool down) { foreach(var code in codes) SendKeyboardEvent(code,down); }
    public string GetClipboardText() => desktop.Call(new { type="clipboard-get" }).GetProperty("text").GetString() ?? "";
    public void SetClipboardText(string text) => desktop.Call(new { type="clipboard-set",text });
}
