using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Updates;

internal static class UpdaterProcessChecks
{
    private static string Host => Path.Combine(new DirectoryInfo(RuntimeEnvironment.GetRuntimeDirectory()).Parent!.Parent!.Parent!.FullName,OperatingSystem.IsWindows()?"dotnet.exe":"dotnet");
    private static JsonObject Identity() {
        using var process=Process.GetCurrentProcess();
        if (OperatingSystem.IsWindows()) {
            using var user=WindowsIdentity.GetCurrent();
            return new() { ["user"]=user.User!.Value,["admin"]=new WindowsPrincipal(user).IsInRole(WindowsBuiltInRole.Administrator),
                ["privileges"]=Privileges(user.Token),["session"]=process.SessionId,["env"]=Environment.GetEnvironmentVariable("REMOTETOOL_PROCESS_TEST") };
        }
        return new() { ["user"]=geteuid(),["privileges"]=File.ReadLines("/proc/self/status").First(line=>line.StartsWith("CapEff:")),
            ["env"]=Environment.GetEnvironmentVariable("REMOTETOOL_PROCESS_TEST") };
    }
    private static string Privileges(IntPtr token) {
        GetTokenInformation(token,3,IntPtr.Zero,0,out var size); var buffer=Marshal.AllocHGlobal(size);
        try { if(!GetTokenInformation(token,3,buffer,size,out _))throw new Exception("Cannot read inherited token privileges");
            var bytes=new byte[size];Marshal.Copy(buffer,bytes,0,size);return Convert.ToHexString(System.Security.Cryptography.SHA256.HashData(bytes)); }
        finally {Marshal.FreeHGlobal(buffer);}
    }
    public static async Task<bool> Handle(string[] args) {
        if (args is ["--process-child",var directory]) {
            File.WriteAllText(Path.Combine(directory,"child.json"),Identity().ToJsonString());
            while(!File.Exists(Path.Combine(directory,"finish"))) {File.WriteAllText(Path.Combine(directory,"heartbeat"),DateTime.UtcNow.Ticks.ToString());await Task.Delay(50);}
            return true;
        }
        if(args is ["--process-parent",var output]) {
            while(!File.Exists(Path.Combine(output,"go")))await Task.Delay(20);
            File.WriteAllText(Path.Combine(output,"parent.json"),Identity().ToJsonString());
            try { var child=UpdaterProcess.StartCommand(Host,[typeof(UpdaterProcessChecks).Assembly.Location,"--process-child",output],output);
                File.WriteAllText(Path.Combine(output,"launch.json"),System.Text.Json.JsonSerializer.Serialize(child)); }
            catch(Exception error){File.WriteAllText(Path.Combine(output,"error"),error.Message);}
            return true;
        }
        if(args is ["--process-check-only"]) {await Run();return true;}
        return false;
    }
    public static async Task Run() {
        var checks=0;
        foreach(var blocked in OperatingSystem.IsWindows()?new[]{false,true}:new[]{false}) {
            var directory=Path.Combine(Path.GetTempPath(),"remotetool process test "+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(directory);
            IntPtr job=IntPtr.Zero;
            var info=new ProcessStartInfo(Host) {UseShellExecute=false,CreateNoWindow=true};
            info.ArgumentList.Add(typeof(UpdaterProcessChecks).Assembly.Location);info.ArgumentList.Add("--process-parent");info.ArgumentList.Add(directory);
            info.Environment["REMOTETOOL_PROCESS_TEST"]="inherited-value";
            using var parent=Process.Start(info)!;
            try {
                if(OperatingSystem.IsWindows()) {
                    job=CreateJobObject(IntPtr.Zero,null);if(job==IntPtr.Zero)throw new Exception("CreateJobObject failed");
                    var limits=new ExtendedLimits {Basic=new BasicLimits {Flags=0x2000u|(blocked?0u:0x800u)}};
                    var size=Marshal.SizeOf<ExtendedLimits>();var buffer=Marshal.AllocHGlobal(size);
                    try {Marshal.StructureToPtr(limits,buffer,false);if(!SetInformationJobObject(job,9,buffer,(uint)size)||!AssignProcessToJobObject(job,parent.Handle))throw new Exception("Cannot configure test job");}
                    finally{Marshal.FreeHGlobal(buffer);}
                }
                File.WriteAllText(Path.Combine(directory,"go"),"");
                await parent.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(15));
                if(job!=IntPtr.Zero){CloseHandle(job);job=IntPtr.Zero;}
                if(blocked) {
                    if(!File.Exists(Path.Combine(directory,"error"))||File.Exists(Path.Combine(directory,"launch.json")))throw new Exception("Denied breakaway must fail before accepting launch");checks++;
                    continue;
                }
                var deadline=DateTime.UtcNow.AddSeconds(10);
                while(!File.Exists(Path.Combine(directory,"child.json"))&&DateTime.UtcNow<deadline)await Task.Delay(50);
                if(File.Exists(Path.Combine(directory,"error")))throw new Exception(File.ReadAllText(Path.Combine(directory,"error")));
                var before=JsonNode.Parse(File.ReadAllText(Path.Combine(directory,"parent.json")))!;
                var after=JsonNode.Parse(File.ReadAllText(Path.Combine(directory,"child.json")))!;
                if(!JsonNode.DeepEquals(before,after))throw new Exception("Parent/child identity, privileges or environment changed: "+before+" -> "+after);checks++;
                await Task.Delay(200);var heartbeat=File.ReadAllText(Path.Combine(directory,"heartbeat"));await Task.Delay(200);
                if(File.ReadAllText(Path.Combine(directory,"heartbeat"))==heartbeat)throw new Exception("Updater did not survive parent/job shutdown");checks++;
                File.WriteAllText(Path.Combine(directory,"finish"),"");
                var launched=System.Text.Json.JsonSerializer.Deserialize<UpdaterProcessIdentity>(File.ReadAllText(Path.Combine(directory,"launch.json")))!;
                try {using var child=Process.GetProcessById(launched.Pid);await child.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(5));}catch(ArgumentException){}
                Console.WriteLine($"Direct updater: identity={before["user"]}, privileges/environment retained; parent exited, child survived");
            } finally {
                if(job!=IntPtr.Zero)CloseHandle(job);
                if(!parent.HasExited){parent.Kill(true);await parent.WaitForExitAsync();}
                File.WriteAllText(Path.Combine(directory,"finish"),"");
                await Task.Delay(100);
                Directory.Delete(directory,true);
            }
        }
        Console.WriteLine($"Direct updater: {checks} real-process/job/privilege checks passed");
    }
    [DllImport("libc")]private static extern uint geteuid();
    [DllImport("advapi32.dll",SetLastError=true)]private static extern bool GetTokenInformation(IntPtr token,int type,IntPtr data,int size,out int needed);
    [StructLayout(LayoutKind.Sequential)]private struct BasicLimits {public long ProcessTime,JobTime;public uint Flags;public UIntPtr Min,Max;public uint Active;public IntPtr Affinity;public uint Priority,Scheduling;}
    [StructLayout(LayoutKind.Sequential)]private struct IoCounters {public ulong A,B,C,D,E,F;}
    [StructLayout(LayoutKind.Sequential)]private struct ExtendedLimits {public BasicLimits Basic;public IoCounters Io;public UIntPtr ProcessMemory,JobMemory,PeakProcess,PeakJob;}
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode)]private static extern IntPtr CreateJobObject(IntPtr attributes,string? name);
    [DllImport("kernel32.dll",SetLastError=true)]private static extern bool SetInformationJobObject(IntPtr job,int type,IntPtr info,uint length);
    [DllImport("kernel32.dll",SetLastError=true)]private static extern bool AssignProcessToJobObject(IntPtr job,IntPtr process);
    [DllImport("kernel32.dll")]private static extern bool CloseHandle(IntPtr handle);
}
