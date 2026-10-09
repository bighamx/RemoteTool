
using System.Collections.Generic;

namespace ChuckieHelper.WebApi.Models.RemoteControl;

/// <summary>
/// 进程信息
/// </summary>
public class ProcessInfo
{
    /// <summary>进程 ID</summary>
    public int Id { get; set; }

    /// <summary>进程名称</summary>
    public string Name { get; set; } = "";

    /// <summary>内存占用 (MB)</summary>
    public double MemoryMB { get; set; }

    /// <summary>线程数</summary>
    public int Threads { get; set; }

    /// <summary>启动时间</summary>
    public string StartTime { get; set; } = "";


    /// <summary>是否有主窗口</summary>
    public bool HasWindow { get; set; }

    /// <summary>主窗口标题</summary>
    public string WindowTitle { get; set; } = "";

    /// <summary>文件描述（产品名称或文件说明）</summary>
    public string Description { get; set; } = "";

    /// <summary>进程文件路径</summary>
    public string FilePath { get; set; } = "";

    /// <summary>CPU 使用百分比</summary>
    public double CpuPercent { get; set; }
}

/// <summary>
/// 系统信息
/// </summary>
public class SystemInfo
{
    /// <summary>运行平台，如 Windows / Linux，供前端区分功能</summary>
    public string Platform { get; set; } = "";

    /// <summary>程序版本</summary>
    public string Version { get; set; } = "";

    /// <summary>详细版本信息（构建时间等）</summary>
    public string InformationalVersion { get; set; } = "";

    // 基本信息
    public string MachineName { get; set; } = "";
    public string UserName { get; set; } = "";
    public string OSVersion { get; set; } = "";
    public int ProcessorCount { get; set; }
    public bool Is64Bit { get; set; }
    public string SystemDirectory { get; set; } = "";
    public string UpTime { get; set; } = "";

    // CPU 信息
    public string CpuName { get; set; } = "";
    public int CpuCores { get; set; }
    public int CpuLogicalProcessors { get; set; }
    public int CpuMaxClockSpeedMHz { get; set; }
    public int CpuUsagePercent { get; set; }
    public double CpuTemperature { get; set; } = -1;

    // GPU 信息
    public List<GpuInfo> Gpus { get; set; } = new();

    // 内存信息
    public long TotalMemoryMB { get; set; }
    public long UsedMemoryMB { get; set; }
    public long AvailableMemoryMB { get; set; }
    public int MemoryUsagePercent { get; set; }

    // 磁盘信息
    public List<DriveInfoDto> Drives { get; set; } = new();

    // 网络适配器
    public List<NetworkAdapterInfo> NetworkAdapters { get; set; } = new();
}

/// <summary>
/// GPU 信息
/// </summary>
public class GpuInfo
{
    public string Name { get; set; } = "";
    public long MemoryMB { get; set; }
    public long MemoryUsedMB { get; set; }
    public string DriverVersion { get; set; } = "";
    public int Temperature { get; set; } = -1;
    public int UsagePercent { get; set; } = -1;
}

/// <summary>
/// 磁盘信息
/// </summary>
public class DriveInfoDto
{
    public string Name { get; set; } = "";
    public double TotalGB { get; set; }
    public double UsedGB { get; set; }
    public double FreeGB { get; set; }
    public int UsagePercent { get; set; }
    public string DriveFormat { get; set; } = "";
}

/// <summary>
/// 网络适配器信息
/// </summary>
public class NetworkAdapterInfo
{
    public string Name { get; set; } = "";
    public long SpeedMbps { get; set; }
    public string MacAddress { get; set; } = "";
}
