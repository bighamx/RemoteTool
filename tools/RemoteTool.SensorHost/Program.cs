using LibreHardwareMonitor.Hardware;
using System.Diagnostics;
using System.Text.Json;

// An isolated, read-only sensor process. Never call IControl.SetSoftware/SetDefault or alter fan settings.
var once = args.Contains("--once");
var parentIndex = Array.IndexOf(args,"--parent");
var parentId = parentIndex>=0&&parentIndex+1<args.Length?int.Parse(args[parentIndex+1]):0;
var computer = new Computer {
    IsCpuEnabled=true, IsGpuEnabled=true, IsMotherboardEnabled=true,
    IsMemoryEnabled=true, IsStorageEnabled=true,
    IsControllerEnabled=false, IsPsuEnabled=false, IsNetworkEnabled=false
};
Console.OutputEncoding = new System.Text.UTF8Encoding(false);
var driverInstalled = Microsoft.Win32.Registry.GetValue(@"HKEY_LOCAL_MACHINE\SYSTEM\CurrentControlSet\Services\PawnIO","ImagePath",null)!=null;
using var identity=System.Security.Principal.WindowsIdentity.GetCurrent();
var isElevated=identity.IsSystem||new System.Security.Principal.WindowsPrincipal(identity).IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
try { computer.Open(); }
catch(Exception ex){Console.Error.WriteLine("Sensor initialization failed: "+ex.Message);return 1;}
try {
    do {
        if(parentId>0){try{using var parent=Process.GetProcessById(parentId);if(parent.HasExited)break;}catch(ArgumentException){break;}}
        var sensors=new List<object>();
        foreach(var hardware in computer.Hardware)Read(hardware,sensors);
        Console.WriteLine(JsonSerializer.Serialize(new{timestamp=DateTimeOffset.UtcNow,source="LibreHardwareMonitor 0.9.6",driverInstalled,isElevated,sensors},new JsonSerializerOptions{PropertyNamingPolicy=JsonNamingPolicy.CamelCase}));
        if(!once)await Task.Delay(3000);
    } while(!once);
} finally { computer.Close(); }
return 0;

static void Read(IHardware hardware,List<object> result){
    try{
        hardware.Update();
        foreach(var sensor in hardware.Sensors){
            if(sensor.SensorType is not (SensorType.Temperature or SensorType.Fan))continue;
            if(sensor.SensorType==SensorType.Temperature&&new[]{"Distance to TjMax","Resolution"," Limit","Warning Temperature","Critical Temperature"}.Any(x=>sensor.Name.Contains(x,StringComparison.OrdinalIgnoreCase)))continue;
            if(sensor.Value is not float value||!float.IsFinite(value))continue;
            if(sensor.SensorType==SensorType.Temperature&&(value<-30||value>150))continue;
            if(sensor.SensorType==SensorType.Fan&&(value<0||value>30000))continue;
            result.Add(new{id=sensor.Identifier.ToString(),name=sensor.Name,hardware=hardware.Name,hardwareType=hardware.HardwareType.ToString(),kind=sensor.SensorType.ToString(),unit=sensor.SensorType==SensorType.Fan?"RPM":"°C",value=Math.Round(value,1)});
        }
    }catch(Exception ex){Console.Error.WriteLine("Sensor read failed for "+hardware.HardwareType+": "+ex.Message);}
    foreach(var child in hardware.SubHardware)Read(child,result);
}
