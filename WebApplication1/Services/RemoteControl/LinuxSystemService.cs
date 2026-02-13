using ChuckieHelper.WebApi.Models.RemoteControl;
using System.Diagnostics;
using System.Runtime.InteropServices;

using System.Reflection;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

/// <summary>
/// Linux 下的系统控制服务实现
/// </summary>
public class LinuxSystemService : ISystemControlService
{
    public void Shutdown()
    {
        // Linux 常见关机命令
        Process.Start("shutdown", "-h now");
    }

    public void CancelShutdown()
    {
        // 取消关机
        Process.Start("shutdown", "-c");
    }

    public void Reboot()
    {
        // 重启
        Process.Start("reboot");
    }

    public void Sleep()
    {
        // 尝试挂起
        Process.Start("systemctl", "suspend");
    }

    public void Hibernate()
    {
        // 尝试休眠
        Process.Start("systemctl", "hibernate");
    }

    public async Task<List<ProcessInfo>> GetProcessListAsync()
    {
        var list = new List<ProcessInfo>();
        try
        {
            var procDir = new DirectoryInfo("/proc");
            if (!procDir.Exists) return list;

            foreach (var dir in procDir.GetDirectories())
            {
                if (!int.TryParse(dir.Name, out var pid)) continue;
                try
                {
                    var (name, cmdline, exePath) = GetLinuxProcessNameAndPath(pid);
                    var (memoryMb, startTime, cpuPercent) = GetLinuxProcessStats(pid);
                    list.Add(new ProcessInfo
                    {
                        Id = pid,
                        Name = name,
                        MemoryMB = memoryMb,
                        Threads = 0,
                        StartTime = startTime,
                        HasWindow = false,
                        WindowTitle = "",
                        Description = string.IsNullOrEmpty(cmdline) ? "" : cmdline.Length > 80 ? cmdline[..80] + "…" : cmdline,
                        FilePath = exePath ?? "",
                        CpuPercent = cpuPercent
                    });
                }
                catch { /* 忽略无权限或已退出的进程 */ }
            }

            list = list.OrderByDescending(p => p.MemoryMB).ToList();
        }
        catch (Exception ex)
        {
            Console.WriteLine($"GetProcessListLinux error: {ex.Message}");
        }

        return await Task.FromResult(list);
    }

    public bool KillProcess(int processId)
    {
        try
        {
            var process = Process.GetProcessById(processId);
            process.Kill(true);
            return true;
        }
        catch (Exception ex)
        {
            Console.WriteLine($"KillProcess error: {ex.Message}");
            return false;
        }
    }

    public void LockWorkstation()
    {
        // Linux 锁屏通常依赖桌面环境，这里尝试常见命令
        try
        {
            // GNOME
            Process.Start("dbus-send", "--type=method_call --dest=org.gnome.ScreenSaver /org/gnome/ScreenSaver org.gnome.ScreenSaver.Lock");
        }
        catch
        {
            try
            {
                // xdg-screensaver
                Process.Start("xdg-screensaver", "lock");
            }
            catch { }
        }
    }

    public byte[] CaptureScreen()
    {
        // Linux 截图通常依赖外部工具，如 scrot, gnome-screenshot 等
        //这里简单实现为一个空操作或者尝试调用常见工具
        // 实际生产环境可能需要更复杂的处理（如 X11/Wayland 交互）
        return Array.Empty<byte>();
    }

    public SystemInfo GetSystemInfo()
    {
        var info = new SystemInfo
        {
            Platform = "Linux",
            MachineName = Environment.MachineName,
            UserName = Environment.UserName,
            OSVersion = GetLinuxOsVersion(),
            ProcessorCount = Environment.ProcessorCount,
            Is64Bit = Environment.Is64BitOperatingSystem,
            SystemDirectory = "",
            UpTime = GetLinuxUptime()
        };

        GetLinuxCpuInfo(info);
        GetLinuxMemoryInfo(info);
        GetLinuxGpuInfo(info);
        GetLinuxDriveInfo(info);
        GetLinuxNetworkInfo(info);
        GetLinuxCpuTemperature(info);

        // 获取版本信息
        var assembly = Assembly.GetEntryAssembly();
        var assemblyName = assembly?.GetName();
        info.Version = assemblyName?.Version?.ToString() ?? "";
        info.InformationalVersion = assembly?.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "";

        return info;
    }

    public async Task<byte[]> CaptureScreenAsync()
    {
        return await Task.FromResult(CaptureScreen());
    }

    public void SendMouseClick(double normalizedX, double normalizedY) { }
    public void SendMouseRightClick(double normalizedX, double normalizedY) { }
    public void SendMouseMiddleClick(double normalizedX, double normalizedY) { }
    public void SendMouseDown(double normalizedX, double normalizedY, int button = 0) { }
    public void SendMouseUp(double normalizedX, double normalizedY, int button = 0) { }
    public void SendMouseMove(double normalizedX, double normalizedY) { }
    public void SendMouseWheel(double normalizedX, double normalizedY, int delta) { }

    public void SendKeyboardEvent(byte vkCode, bool isKeyDown) { }
    public void SendKeyboardEvents(byte[] vkCodes, bool isKeyDown) { }

    public string GetClipboardText() { return ""; }
    public void SetClipboardText(string text) { }

    #region Linux Helpers

    private static (string name, string cmdline, string? exePath) GetLinuxProcessNameAndPath(int pid)
    {
        var name = "";
        var cmdline = "";
        string? exePath = null;
        try
        {
            var commPath = $"/proc/{pid}/comm";
            if (File.Exists(commPath))
                name = File.ReadAllText(commPath).Trim().TrimEnd('\n');
            var cmdPath = $"/proc/{pid}/cmdline";
            if (File.Exists(cmdPath))
            {
                var raw = File.ReadAllBytes(cmdPath);
                cmdline = raw.Length == 0 ? "" : System.Text.Encoding.UTF8.GetString(raw).Replace('\0', ' ').Trim();
                if (string.IsNullOrEmpty(name) && cmdline.Length > 0)
                    name = cmdline.Split(' ')[0].Split('/').LastOrDefault() ?? "";
            }
            var exeLink = $"/proc/{pid}/exe";
            if (File.Exists(exeLink))
            {
                try
                {
                    exePath = System.IO.File.ResolveLinkTarget(exeLink, false)?.FullName;
                }
                catch { }
            }
        }
        catch { }
        if (string.IsNullOrEmpty(name))
            name = pid.ToString();
        return (name, cmdline, exePath);
    }

    private static (double memoryMb, string startTime, double cpuPercent) GetLinuxProcessStats(int pid)
    {
        double memoryMb = 0;
        string startTime = "-";
        double cpuPercent = 0;
        try
        {
            var statPath = $"/proc/{pid}/stat";
            if (!File.Exists(statPath)) return (0, "-", 0);
            var stat = File.ReadAllText(statPath);
            // format: pid (comm) state ppid ... starttime(20th) vsize rss(22nd) ...
            var closeParen = stat.IndexOf(')');
            if (closeParen < 0) return (0, "-", 0);
            var afterComm = stat[(closeParen + 1)..].TrimStart().Split(' ', StringSplitOptions.RemoveEmptyEntries);
            if (afterComm.Length > 21)
            {
                if (long.TryParse(afterComm[21], out var rss))
                    memoryMb = Math.Round(rss * 4096.0 / 1024.0 / 1024.0, 1); // page size 4KB
                if (long.TryParse(afterComm[19], out var startTicks))
                {
                    try
                    {
                        var boot = GetLinuxBootTime();
                        var startSec = boot + startTicks / 100.0;
                        var startDate = DateTimeOffset.FromUnixTimeSeconds((long)startSec);
                        startTime = startDate.LocalDateTime.ToString("yyyy-MM-dd HH:mm:ss");
                    }
                    catch { }
                }
            }
        }
        catch { }
        return (memoryMb, startTime, cpuPercent);
    }

    private static long GetLinuxBootTime()
    {
        try
        {
            var line = File.ReadAllText("/proc/uptime").Trim().Split(' ')[0];
            var uptimeSec = double.Parse(line, System.Globalization.CultureInfo.InvariantCulture);
            return (long)(DateTimeOffset.UtcNow.ToUnixTimeSeconds() - uptimeSec);
        }
        catch { }
        return 0;
    }

    private static string GetLinuxOsVersion()
    {
        try
        {
            if (File.Exists("/proc/version"))
                return File.ReadAllText("/proc/version").Trim().Replace("\n", " ");
        }
        catch { }
        return Environment.OSVersion.ToString();
    }

    private static string GetLinuxUptime()
    {
        try
        {
            var line = File.ReadAllText("/proc/uptime").Trim();
            var seconds = double.Parse(line.Split(' ', StringSplitOptions.RemoveEmptyEntries)[0], System.Globalization.CultureInfo.InvariantCulture);
            var ts = TimeSpan.FromSeconds(seconds);
            return $"{(int)ts.TotalDays}.{ts.Hours:D2}:{ts.Minutes:D2}:{ts.Seconds:D2}";
        }
        catch { }
        return "-";
    }

    private static void GetLinuxCpuInfo(SystemInfo info)
    {
        try
        {
            var cpuinfo = File.Exists("/proc/cpuinfo") ? File.ReadAllText("/proc/cpuinfo") : "";
            var lines = cpuinfo.Split('\n');
            foreach (var line in lines)
            {
                if (line.StartsWith("model name", StringComparison.OrdinalIgnoreCase))
                {
                    var colon = line.IndexOf(':');
                    if (colon >= 0)
                        info.CpuName = line[(colon + 1)..].Trim();
                    break;
                }
            }
            foreach (var line in lines)
            {
                if (line.StartsWith("cpu cores", StringComparison.OrdinalIgnoreCase))
                {
                    var colon = line.IndexOf(':');
                    if (colon >= 0 && int.TryParse(line[(colon + 1)..].Trim(), out var cores))
                        info.CpuCores = cores;
                    break;
                }
            }
            foreach (var line in lines)
            {
                if (line.StartsWith("cpu MHz", StringComparison.OrdinalIgnoreCase))
                {
                    var colon = line.IndexOf(':');
                    if (colon >= 0 && double.TryParse(line[(colon + 1)..].Trim(), System.Globalization.NumberStyles.Any, System.Globalization.CultureInfo.InvariantCulture, out var mhz))
                        info.CpuMaxClockSpeedMHz = (int)Math.Round(mhz);
                    break;
                }
            }
            if (info.CpuCores <= 0)
                info.CpuCores = info.ProcessorCount;
            info.CpuLogicalProcessors = info.ProcessorCount;
        }
        catch (Exception ex) { Console.WriteLine($"GetLinuxCpuInfo: {ex.Message}"); }

        try
        {
            var stat = File.Exists("/proc/stat") ? File.ReadAllText("/proc/stat") : "";
            var firstLine = stat.Split('\n').FirstOrDefault(l => l.StartsWith("cpu ", StringComparison.Ordinal));
            if (firstLine != null)
            {
                var parts = firstLine.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length >= 8 && long.TryParse(parts[1], out var user) && long.TryParse(parts[2], out var nice) &&
                    long.TryParse(parts[3], out var sys) && long.TryParse(parts[4], out var idle) &&
                    long.TryParse(parts[5], out var iowait) && long.TryParse(parts[6], out var irq) &&
                    long.TryParse(parts[7], out var softirq))
                {
                    var total = user + nice + sys + idle + iowait + irq + softirq;
                    var used = total - idle;
                    if (total > 0)
                        info.CpuUsagePercent = (int)Math.Round(used * 100.0 / total);
                }
            }
        }
        catch (Exception ex) { Console.WriteLine($"GetLinuxCpuUsage: {ex.Message}"); }
    }

    private static void GetLinuxMemoryInfo(SystemInfo info)
    {
        try
        {
            var meminfo = File.Exists("/proc/meminfo") ? File.ReadAllText("/proc/meminfo") : "";
            long totalKb = 0, availableKb = 0;
            foreach (var line in meminfo.Split('\n'))
            {
                var parts = line.Split((char[]?)null, StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length < 2) continue;
                if (line.StartsWith("MemTotal:"))
                    long.TryParse(parts[1], out totalKb);
                else if (line.StartsWith("MemAvailable:"))
                    long.TryParse(parts[1], out availableKb);
                else if (line.StartsWith("MemFree:") && availableKb == 0)
                    long.TryParse(parts[1], out availableKb);
            }
            if (totalKb > 0)
            {
                info.TotalMemoryMB = totalKb / 1024;
                info.AvailableMemoryMB = availableKb > 0 ? availableKb / 1024 : totalKb / 1024;
                info.UsedMemoryMB = info.TotalMemoryMB - info.AvailableMemoryMB;
                info.MemoryUsagePercent = (int)Math.Round(info.UsedMemoryMB * 100.0 / info.TotalMemoryMB);
            }
        }
        catch (Exception ex) { Console.WriteLine($"GetLinuxMemoryInfo: {ex.Message}"); }
    }

    private static void GetLinuxGpuInfo(SystemInfo info)
    {
        var gpus = new List<GpuInfo>();
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "lspci",
                Arguments = "-v -nn",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc != null)
            {
                var output = proc.StandardOutput.ReadToEnd();
                proc.WaitForExit(3000);
                foreach (var line in output.Split('\n'))
                {
                    if (line.Contains("VGA", StringComparison.OrdinalIgnoreCase) || line.Contains("3D", StringComparison.OrdinalIgnoreCase))
                    {
                        var name = line.Trim();
                        if (name.Length > 0 && !gpus.Any(g => g.Name == name))
                            gpus.Add(new GpuInfo { Name = name, MemoryMB = 0 });
                    }
                }
            }
        }
        catch { }
        if (gpus.Count == 0)
        {
            try
            {
                var drm = new DirectoryInfo("/sys/class/drm");
                if (drm.Exists)
                {
                    foreach (var card in drm.GetDirectories().Where(d => d.Name.StartsWith("card", StringComparison.Ordinal)))
                    {
                        var nameFile = Path.Combine(card.FullName, "device", "product_name");
                        var name = File.Exists(nameFile) ? File.ReadAllText(nameFile).Trim() : card.Name;
                        if (!string.IsNullOrEmpty(name) && !gpus.Any(g => g.Name == name))
                            gpus.Add(new GpuInfo { Name = name, MemoryMB = 0 });
                    }
                }
            }
            catch { }
        }
        info.Gpus = gpus;
        TryGetNvidiaGpuStats(info.Gpus);
    }

    private static void GetLinuxDriveInfo(SystemInfo info)
    {
        var drives = new List<DriveInfoDto>();
        try
        {
            var mounts = File.Exists("/proc/mounts") ? File.ReadAllLines("/proc/mounts") : Array.Empty<string>();
            var seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (var line in mounts)
            {
                var parts = line.Split(' ', StringSplitOptions.RemoveEmptyEntries);
                if (parts.Length < 4) continue;
                var mountPoint = parts[1];
                var fstype = parts[2];
                if (mountPoint.StartsWith("/sys") || mountPoint.StartsWith("/proc") || mountPoint.StartsWith("/dev"))
                    continue;
                if (seen.Contains(mountPoint)) continue;
                seen.Add(mountPoint);
                try
                {
                    var (totalGb, freeGb) = GetLinuxDiskSpace(mountPoint);
                    if (totalGb <= 0) continue;
                    var usedGb = Math.Round(totalGb - freeGb, 1);
                    drives.Add(new DriveInfoDto
                    {
                        Name = mountPoint,
                        TotalGB = totalGb,
                        UsedGB = usedGb,
                        FreeGB = Math.Round(freeGb, 1),
                        UsagePercent = totalGb > 0 ? (int)Math.Round(usedGb / totalGb * 100) : 0,
                        DriveFormat = fstype
                    });
                }
                catch { }
            }
            info.Drives = drives;
        }
        catch (Exception ex) { Console.WriteLine($"GetLinuxDriveInfo: {ex.Message}"); }
    }

    private static (double totalGb, double freeGb) GetLinuxDiskSpace(string path)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "df",
                Arguments = $"-B1 --output=size,avail \"{path}\"",
                UseShellExecute = false,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                CreateNoWindow = true
            };
            using var proc = Process.Start(psi);
            if (proc != null)
            {
                var output = proc.StandardOutput.ReadToEnd().Trim();
                proc.WaitForExit(2000);
                var lines = output.Split('\n');
                if (lines.Length >= 2)
                {
                    var parts = lines[1].Split(' ', StringSplitOptions.RemoveEmptyEntries);
                    if (parts.Length >= 2 && long.TryParse(parts[0], out var total) && long.TryParse(parts[1], out var avail))
                        return (total / 1024.0 / 1024.0 / 1024.0, avail / 1024.0 / 1024.0 / 1024.0);
                }
            }
        }
        catch { }
        return (0, 0);
    }

    private static void GetLinuxNetworkInfo(SystemInfo info)
    {
        var list = new List<NetworkAdapterInfo>();
        try
        {
            var net = new DirectoryInfo("/sys/class/net");
            if (!net.Exists) return;
            foreach (var dir in net.GetDirectories())
            {
                if (dir.Name == "lo") continue;
                var addrPath = Path.Combine(dir.FullName, "address");
                var addr = File.Exists(addrPath) ? File.ReadAllText(addrPath).Trim() : "";
                var speedPath = Path.Combine(dir.FullName, "speed");
                var speedMbps = 0L;
                if (File.Exists(speedPath) && int.TryParse(File.ReadAllText(speedPath).Trim(), out var sp))
                    speedMbps = sp;
                list.Add(new NetworkAdapterInfo { Name = dir.Name, SpeedMbps = speedMbps, MacAddress = addr });
            }
            info.NetworkAdapters = list;
        }
        catch (Exception ex) { Console.WriteLine($"GetLinuxNetworkInfo: {ex.Message}"); }
    }

    private static void GetLinuxCpuTemperature(SystemInfo info)
    {
        try
        {
            var thermal = new DirectoryInfo("/sys/class/thermal");
            if (!thermal.Exists) return;
            foreach (var zone in thermal.GetDirectories().Where(d => d.Name.StartsWith("thermal_zone")))
            {
                var tempPath = Path.Combine(zone.FullName, "temp");
                if (!File.Exists(tempPath)) continue;
                var raw = File.ReadAllText(tempPath).Trim();
                if (int.TryParse(raw, out var millideg) && millideg > 0 && millideg < 150000)
                {
                    info.CpuTemperature = Math.Round(millideg / 1000.0, 1);
                    break;
                }
            }
        }
        catch { }
    }

    /// <summary>
    /// 尝试通过 nvidia-smi 获取 NVIDIA GPU 温度和占用率
    /// </summary>
    private static void TryGetNvidiaGpuStats(List<GpuInfo> gpus)
    {
        try
        {
            var psi = new ProcessStartInfo
            {
                FileName = "nvidia-smi",
                Arguments = "--query-gpu=name,temperature.gpu,utilization.gpu,memory.used,memory.total --format=csv,noheader,nounits",
                RedirectStandardOutput = true,
                UseShellExecute = false,
                CreateNoWindow = true
            };

            using var proc = Process.Start(psi);
            if (proc == null) return;

            var output = proc.StandardOutput.ReadToEnd();
            proc.WaitForExit(3000);

            var lines = output.Trim().Split('\n', StringSplitOptions.RemoveEmptyEntries);
            foreach (var line in lines)
            {
                var parts = line.Split(',', StringSplitOptions.TrimEntries);
                if (parts.Length < 5) continue;

                var nvidiaName = parts[0];
                // 匹配到对应的 GPU
                var matchedGpu = gpus.FirstOrDefault(g =>
                    g.Name.Contains("NVIDIA", StringComparison.OrdinalIgnoreCase) &&
                    (g.Name.Contains(nvidiaName, StringComparison.OrdinalIgnoreCase) ||
                     nvidiaName.Contains(g.Name.Replace("NVIDIA ", ""), StringComparison.OrdinalIgnoreCase)));

                if (matchedGpu == null && gpus.Any(g => g.Name.Contains("NVIDIA", StringComparison.OrdinalIgnoreCase)))
                {
                    matchedGpu = gpus.First(g => g.Name.Contains("NVIDIA", StringComparison.OrdinalIgnoreCase));
                }

                if (matchedGpu != null)
                {
                    if (int.TryParse(parts[1], out var temp)) matchedGpu.Temperature = temp;
                    if (int.TryParse(parts[2], out var usage)) matchedGpu.UsagePercent = usage;
                    if (long.TryParse(parts[3], out var memUsed)) matchedGpu.MemoryUsedMB = memUsed;
                    if (long.TryParse(parts[4], out var memTotal) && memTotal > 0) matchedGpu.MemoryMB = memTotal;
                }
            }
        }
        catch
        {
            // nvidia-smi 不可用（非 NVIDIA GPU 或未安装驱动）
        }
    }

    #endregion
}
