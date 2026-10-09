using System.Diagnostics;
using System.Management;
using System.Net.NetworkInformation;
using RemoteTool.WebApi.Models.RemoteControl;

namespace RemoteTool.WebApi.Services.RemoteControl;

public sealed record NetworkRate(string Name, double ReceiveBytesPerSecond, double SendBytesPerSecond);
public sealed record TemperatureReading(string Name, double Celsius,string Id="",string Hardware="",string Source="");
public sealed record FanReading(string Name,double Rpm,string Id,string Hardware);
public sealed record PerformanceSample(DateTimeOffset Timestamp, double CpuPercent, double MemoryPercent,
    long UsedMemoryMB, long TotalMemoryMB, NetworkRate[] Networks, TemperatureReading[] Temperatures, double[] CpuCores,FanReading[] Fans,string SensorStatus);

/// <summary>Bounded host history shared by all clients. Sampling does not depend on a page being open.</summary>
public sealed class PerformanceMonitor(ISystemControlService system,HardwareSensorMonitor hardware, ILogger<PerformanceMonitor> logger) : BackgroundService
{
    private readonly object _gate = new();
    private readonly Queue<PerformanceSample> _history = new();
    private readonly Dictionary<string, (long Rx, long Tx, long Tick)> _network = new();
    private SystemInfo _info;

    public object Snapshot(int seconds)
    {
        var cutoff = DateTimeOffset.UtcNow.AddSeconds(-Math.Clamp(seconds, 60, 900));
        lock (_gate) return new { intervalSeconds = 3, retentionSeconds = 900, info = _info,
            latest = _history.LastOrDefault(), history = _history.Where(x => x.Timestamp >= cutoff).ToArray() };
    }

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        while (!stoppingToken.IsCancellationRequested)
        {
            var started = Stopwatch.GetTimestamp();
            try
            {
                var info = await Task.Run(system.GetSystemInfo, stoppingToken);
                var temperatures = new List<TemperatureReading>();
                if (info.CpuTemperature > 0) temperatures.Add(new(OperatingSystem.IsWindows() ? "系统热区" : "CPU", info.CpuTemperature));
                temperatures.AddRange(info.Gpus.Where(x => x.Temperature >= 0).Select(x => new TemperatureReading(x.Name, x.Temperature)));
                var readings=hardware.Latest;
                if(readings!=null){
                    // Preserve the reported sensor position/name; never relabel a generic temperature as MOS/PCH/socket.
                    var measured=readings.Sensors.Where(x=>x.Kind=="Temperature").Select(x=>new TemperatureReading(x.Name,x.Value,x.Id,x.Hardware,readings.Source)).ToArray();
                    if(measured.Length>0){temperatures.RemoveAll(x=>string.IsNullOrEmpty(x.Id)&&x.Name!="系统热区");temperatures.AddRange(measured);}
                }
                var fans=readings?.Sensors.Where(x=>x.Kind=="Fan").Select(x=>new FanReading(x.Name,x.Value,x.Id,x.Hardware)).ToArray()??Array.Empty<FanReading>();
                var sample = new PerformanceSample(DateTimeOffset.UtcNow, info.CpuUsagePercent, info.MemoryUsagePercent,
                    info.UsedMemoryMB, info.TotalMemoryMB, ReadNetwork(), temperatures.ToArray(), ReadCpuCores(),fans,hardware.Status);
                lock (_gate)
                {
                    _info = info;
                    _history.Enqueue(sample);
                    while (_history.Count > 301 || _history.Count > 0 && _history.Peek().Timestamp < sample.Timestamp.AddMinutes(-15)) _history.Dequeue();
                }
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception ex) { logger.LogWarning(ex, "Performance sampling failed"); }
            var elapsed = Stopwatch.GetElapsedTime(started);
            if (elapsed < TimeSpan.FromSeconds(3)) await Task.Delay(TimeSpan.FromSeconds(3) - elapsed, stoppingToken);
        }
    }

    private NetworkRate[] ReadNetwork()
    {
        var rates = new List<NetworkRate>();
        var tick = Stopwatch.GetTimestamp();
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (adapter.OperationalStatus != OperationalStatus.Up || adapter.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            try
            {
                var stats = adapter.GetIPStatistics();
                double rx = 0, tx = 0;
                if (_network.TryGetValue(adapter.Id, out var previous))
                {
                    var seconds = (tick - previous.Tick) / (double)Stopwatch.Frequency;
                    if (seconds > 0) { rx = Math.Max(0, stats.BytesReceived - previous.Rx) / seconds; tx = Math.Max(0, stats.BytesSent - previous.Tx) / seconds; }
                }
                _network[adapter.Id] = (stats.BytesReceived, stats.BytesSent, tick);
                rates.Add(new(adapter.Name, Math.Round(rx), Math.Round(tx)));
            }
            catch (NetworkInformationException) { }
        }
        return rates.ToArray();
    }

    private double[] ReadCpuCores()
    {
        if (!OperatingSystem.IsWindows()) return Array.Empty<double>();
        try
        {
            using var query = new ManagementObjectSearcher("SELECT Name, PercentProcessorTime FROM Win32_PerfFormattedData_PerfOS_Processor");
            return query.Get().Cast<ManagementObject>().Where(x => x["Name"]?.ToString() != "_Total")
                .OrderBy(x => int.TryParse(x["Name"]?.ToString(), out var n) ? n : int.MaxValue)
                .Select(x => Math.Clamp(Convert.ToDouble(x["PercentProcessorTime"]), 0, 100)).ToArray();
        }
        catch { return Array.Empty<double>(); }
    }
}
