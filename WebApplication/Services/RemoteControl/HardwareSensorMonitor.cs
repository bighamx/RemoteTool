using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.RemoteControl;

public sealed record HardwareReading(string Id,string Name,string Hardware,string HardwareType,string Kind,string Unit,double Value);
public sealed record HardwareSnapshot(DateTimeOffset Timestamp,string Source,HardwareReading[] Sensors,bool DriverInstalled=false,bool IsElevated=false);

/// <summary>Isolates hardware-driver dependencies from the web process and exposes only fresh read-only telemetry.</summary>
public sealed class HardwareSensorMonitor(ILogger<HardwareSensorMonitor> logger):BackgroundService
{
    private HardwareSnapshot _latest;
    private string _status="正在初始化硬件传感器";
    public string Status=>_status;
    public HardwareSnapshot Latest => _latest!=null&&DateTimeOffset.UtcNow-_latest.Timestamp<TimeSpan.FromSeconds(20)?_latest:null;
    protected override async Task ExecuteAsync(CancellationToken token){
        if(!OperatingSystem.IsWindows()){_status="当前平台未启用硬件传感器采集";return;}
        var folderPath = Path.Combine(Path.GetDirectoryName(InteractiveProcessLauncher.GetApplicationDllPath())!, "sensors");
        var path = Path.Combine(folderPath, "RemoteTool.SensorHost.dll");
        // Existing deployments may still have the pre-rename sensor host.
        if (!File.Exists(path)) path = Path.Combine(folderPath, "ChuckieHelper.SensorHost.dll");
        if(!File.Exists(path)){_status="尚未部署硬件传感器采集程序";logger.LogWarning("Sensor host is missing: {Path}",path);return;}
        while(!token.IsCancellationRequested){
            using var process=new Process{StartInfo=new ProcessStartInfo(InteractiveProcessLauncher.GetDotnetPath()){
                Arguments=$"\"{path}\" --parent {Environment.ProcessId}",UseShellExecute=false,CreateNoWindow=true,
                RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=Encoding.UTF8,StandardErrorEncoding=Encoding.UTF8,
                WorkingDirectory=Path.GetDirectoryName(path)!
            }};
            try{
                process.Start();
                var stderr=Task.Run(async()=>{while(await process.StandardError.ReadLineAsync(token) is {} line)logger.LogWarning("Sensor host: {Message}",line);},token);
                while(await process.StandardOutput.ReadLineAsync(token) is {} line){
                    var snapshot=JsonSerializer.Deserialize<HardwareSnapshot>(line,new JsonSerializerOptions{PropertyNameCaseInsensitive=true});
                    if(snapshot==null)continue;
                    _latest=snapshot;_status=$"已读取 {snapshot.Sensors.Length} 个硬件传感器";
                    if(!snapshot.DriverInstalled)_status+=" · CPU/主板采集需要安装 PawnIO";
                    else if(!snapshot.IsElevated)_status+=" · 部分传感器需要服务账户权限";
                    var folder = RemoteToolPaths.Sensors;
                    try{Directory.CreateDirectory(folder);await File.WriteAllTextAsync(Path.Combine(folder,"latest.json"),line,token);}catch(IOException){}
                }
                await process.WaitForExitAsync(token);await stderr;
                _status="硬件采集进程已退出，正在重试";
            }catch(OperationCanceledException)when(token.IsCancellationRequested){break;}
            catch(Exception ex){_status="硬件采集暂不可用";logger.LogWarning(ex,"Hardware sensor collection failed");}
            finally{try{if(!process.HasExited)process.Kill(entireProcessTree:true);}catch(InvalidOperationException){}}
            await Task.Delay(10000,token);
        }
    }
}
