using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.RemoteControl;

public sealed record HardwareReading(string Id,string Name,string Hardware,string HardwareType,string Kind,string Unit,double Value);
public sealed record HardwareSnapshot(DateTimeOffset Timestamp,string Source,HardwareReading[] Sensors,bool DriverInstalled=false,bool IsElevated=false);

/// <summary>Isolates hardware-driver dependencies from the web process and exposes only fresh read-only telemetry.</summary>
public sealed class HardwareSensorMonitor(ILogger<HardwareSensorMonitor> logger):BackgroundService
{
    private const string PawnIoInstallerUrl="https://github.com/namazso/PawnIO.Setup/releases/download/2.2.0/PawnIO_setup.exe";
    private const string PawnIoInstallerSha256="1F519A22E47187F70A1379A48CA604981C4FCF694F4E65B734AAA74A9FBA3032";
    private HardwareSnapshot _latest;
    private string _status="正在初始化硬件传感器";
    private bool _pawnIoAttempted;
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
                    if(!snapshot.DriverInstalled)await TryInstallPawnIoAsync(folder,token);
                }
                await process.WaitForExitAsync(token);await stderr;
                _status="硬件采集进程已退出，正在重试";
            }catch(OperationCanceledException)when(token.IsCancellationRequested){break;}
            catch(Exception ex){_status="硬件采集暂不可用";logger.LogWarning(ex,"Hardware sensor collection failed");}
            finally{try{if(!process.HasExited)process.Kill(entireProcessTree:true);}catch(InvalidOperationException){}}
            await Task.Delay(10000,token);
        }
    }
    /// <summary>Installs the PawnIO kernel driver once per process lifetime so motherboard/CPU sensors appear without manual setup.</summary>
    private async Task TryInstallPawnIoAsync(string folder,CancellationToken token){
        if(_pawnIoAttempted||!OperatingSystem.IsWindows())return;
        _pawnIoAttempted=true;
        var installerPath=Path.Combine(folder,"PawnIO_setup.exe");
        try{
            _status="CPU/主板采集需要 PawnIO 驱动，正在自动安装…";
            logger.LogInformation("Downloading PawnIO installer to {Path}",installerPath);
            using(var http=new System.Net.Http.HttpClient{Timeout=TimeSpan.FromMinutes(3)}){
                await using var stream=await http.GetStreamAsync(PawnIoInstallerUrl,token);
                await using var file=File.Create(installerPath);
                await stream.CopyToAsync(file,token);
            }
            using(var sha=System.Security.Cryptography.SHA256.Create())
            using(var file=File.OpenRead(installerPath)){
                var hash=Convert.ToHexString(sha.ComputeHash(file));
                if(!string.Equals(hash,PawnIoInstallerSha256,StringComparison.OrdinalIgnoreCase))
                    throw new InvalidOperationException($"PawnIO installer hash mismatch: {hash}");
            }
            _status="正在安装 PawnIO 驱动…";
            using var installer=new Process{StartInfo=new ProcessStartInfo(installerPath){
                Arguments="-install -silent",UseShellExecute=false,CreateNoWindow=true,
                RedirectStandardOutput=true,RedirectStandardError=true,WorkingDirectory=folder}};
            installer.Start();
            await installer.WaitForExitAsync(CancellationToken.None).WaitAsync(TimeSpan.FromMinutes(3),token);
            var driverPresent=Microsoft.Win32.Registry.GetValue(@"HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\PawnIO","ImagePath",null)!=null;
            if(installer.ExitCode==0&&driverPresent){
                _status="PawnIO 驱动已自动安装，正在重新采集传感器";
                logger.LogInformation("PawnIO installed successfully");
                try{foreach(var sensor in System.Diagnostics.Process.GetProcessesByName("RemoteTool.SensorHost"))sensor.Kill(entireProcessTree:true);}catch(Exception){/* The monitor loop restarts the host. */}
                try{foreach(var sensor in System.Diagnostics.Process.GetProcessesByName("ChuckieHelper.SensorHost"))sensor.Kill(entireProcessTree:true);}catch(Exception){/* Old process name. */}
            }else{
                _status=$"PawnIO 自动安装失败（exit={installer.ExitCode}），请手动安装以启用 CPU/主板传感器";
                logger.LogWarning("PawnIO installer exit code {Code}, driver present: {Present}",installer.ExitCode,driverPresent);
            }
        }catch(Exception ex){
            _status="PawnIO 自动安装失败，请手动安装以启用 CPU/主板传感器";
            logger.LogWarning(ex,"Automatic PawnIO installation failed");
        }
    }
}
