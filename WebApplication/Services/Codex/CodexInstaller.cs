using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.Extensions.Hosting;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

/** Fixed official package, private prefix, no caller-supplied command/URL and no login mutation. */
public sealed class CodexInstaller
{
    public sealed record Status(string Id,string Phase,string Message,string Version="") { public bool Busy=>Phase is "installing" or "validating"; }
    internal sealed record Output(int ExitCode,string Text);
    private readonly string root;
    private readonly string? npm;
    private readonly string? prefix;
    private readonly Func<string,string[],CancellationToken,Task<Output>> run;
    private readonly CancellationToken stopping;
    private static readonly JsonSerializerOptions Json=new(){PropertyNameCaseInsensitive=true};
    private readonly object gate=new();
    private Status status=new("","idle","");
    private Task completion=Task.CompletedTask;
    public bool CanInstall=>npm!=null;
    /// <summary>安装目标前缀；null 表示交给 npm 的默认前缀。</summary>
    public string? Target=>prefix;
    public Status Current {get{lock(gate)return status;}}
    internal Task Completion=>completion;
    public CodexInstaller(IHostApplicationLifetime lifetime,IConfiguration configuration):this(RemoteToolPaths.CodexManagedTools,FindNpm(),RunProcess,lifetime.ApplicationStopping,ResolvePrefix(configuration["Codex:InstallPrefix"])){}
    internal CodexInstaller(string root,string? npm,Func<string,string[],CancellationToken,Task<Output>> run,CancellationToken stopping=default,string? prefix=null){
        this.root=Path.GetFullPath(root);this.npm=npm;this.run=run;this.stopping=stopping;this.prefix=prefix;
        try{var file=Path.Combine(root,"status.json");if(File.Exists(file)){
            status=JsonSerializer.Deserialize<Status>(File.ReadAllText(file),Json)??status;
            if(status.Busy)status=status with{Phase="failed",Message="服务已重启，安装结果未确认。请重新检测后再试。"};
        }}catch(Exception e)when(e is IOException or JsonException){}
    }
    public Status Start(){
        lock(gate){
            if(status.Busy)return status;
            if(npm==null)throw new InvalidOperationException("此平台未提供自动安装，或服务账户无法使用 npm。请按手动步骤安装。 ");
            status=new(Guid.NewGuid().ToString("N"),"installing",$"正在用 npm 把官方 Codex CLI 安装到{(prefix is null?"系统默认目录":prefix)}；不会更改登录凭据。 ");
            var job=status.Id;completion=Task.Run(()=>Install(job));return status;
        }
    }
    private void Update(Status value){
        lock(gate){status=value;try{Atomic(Path.Combine(root,"status.json"),Obj(("Id",value.Id),("Phase",value.Phase),("Message",value.Message),("Version",value.Version)));}catch(IOException){}}
    }
    private async Task Install(string id){
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(stopping);timeout.CancelAfter(TimeSpan.FromMinutes(15));
        var schema=Path.Combine(Path.GetTempPath(),"codex-schema-"+id);
        try{
            // 用 npm 官方默认前缀安装（不再塞进 RemoteTool 私有目录），装完 codex 即在系统 PATH 上。
            var args=new List<string>{"install","--global"};
            if(prefix is not null){args.Add("--prefix");args.Add(prefix);}
            args.AddRange(["--registry","https://registry.npmjs.org","--no-fund","--no-audit","@openai/codex@latest"]);
            var install=await run(npm!,[..args],timeout.Token);
            if(install.ExitCode!=0)throw new IOException(Permission(install.Text)?"npm 没有写入目标目录的权限（目标目录属于登录用户，服务账户需要该目录的写权限；可调整权限后重试，或按手动步骤安装）":"安装未完成");
            var globalRoot=prefix is null?Last((await run(npm!,["root","--global"],timeout.Token)).Text):Path.Combine(prefix,OperatingSystem.IsWindows()?"node_modules":"lib/node_modules");
            if(globalRoot.Length==0)throw new IOException("无法确定 npm 全局目录");
            Update(new(id,"validating","正在验证 Codex 程序和当前应用所需协议。"));
            var binary=Locate(globalRoot)??throw new IOException("未找到可执行程序");
            var version=await run(binary,["--version"],timeout.Token);
            var match=Regex.Match(version.Text,@"codex-cli\s+([0-9][0-9A-Za-z.\-+]*)");
            if(version.ExitCode!=0||!match.Success)throw new IOException("程序版本验证失败");
            var validation=await run(binary,["app-server","generate-json-schema","--out",schema],timeout.Token);
            if(validation.ExitCode!=0||!new[]{"v2/ThreadStartParams.json","v2/ThreadTurnsListParams.json","v2/TurnStartParams.json"}.All(p=>File.Exists(Path.Combine(schema,p))))
                throw new IOException("该版本缺少所需接口");
            Atomic(Path.Combine(root,"executable.json"),Obj(("executable",binary),("version",match.Groups[1].Value),("source","npm-global"),("root",globalRoot)));
            Update(new(id,"ready","安装完成。请重新检测，再登录账户或配置 Provider。",match.Groups[1].Value));
        }catch(Exception e)when(e is IOException or UnauthorizedAccessException or OperationCanceledException or InvalidOperationException or System.ComponentModel.Win32Exception){
            Update(new(id,"failed",e is IOException&&e.Message.StartsWith("npm 没有")?e.Message:"安装未完成或协议不兼容。请检查服务端联网、npm 与全局目录权限，或按手动步骤安装受支持版本。"));
        }finally{
            try{if(Directory.Exists(schema))Directory.Delete(schema,true);}catch(IOException){}catch(UnauthorizedAccessException){}
        }
    }
    /// <summary>全局安装后的可执行文件：npm 平台包放在 @openai/codex 依赖树的 vendor/&lt;target&gt;/bin 下。</summary>
    private static string? Locate(string globalRoot){
        var package=Path.Combine(globalRoot,"@openai","codex");
        if(!Directory.Exists(package))return null;
        var name=OperatingSystem.IsWindows()?"codex.exe":"codex";
        return Directory.EnumerateFiles(package,name,new EnumerationOptions{RecurseSubdirectories=true,AttributesToSkip=FileAttributes.ReparsePoint,IgnoreInaccessible=true})
            .Where(CodexExecutable.Complete).OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault();
    }
    private static string Last(string text)=>text.Split('\n',StringSplitOptions.RemoveEmptyEntries|StringSplitOptions.TrimEntries).LastOrDefault()??"";
    private static bool Permission(string text)=>new[]{"EACCES","EPERM","permission denied","access is denied","拒绝访问"}.Any(x=>text.Contains(x,StringComparison.OrdinalIgnoreCase));
    /// <summary>安装目标：显式配置优先；其余平台都装到「当前登录用户」的 npm 前缀。</summary>
    private static string? ResolvePrefix(string? configured){
        if(!string.IsNullOrWhiteSpace(configured))return Environment.ExpandEnvironmentVariables(configured.Trim());
        var user=OperatingSystem.IsWindows()?ConsoleUserName():UnixConsoleUser();
        var home=ConsoleUserHome();
        Trace($"user={Environment.UserName} console={user ?? "(null)"} home={home ?? "(null)"}");
        if(OperatingSystem.IsWindows())return string.IsNullOrWhiteSpace(home)?null:Path.Combine(home,"AppData","Roaming","npm");
        if(OperatingSystem.IsWindows())return string.IsNullOrWhiteSpace(home)?null:Path.Combine(home,"AppData","Roaming","npm");
        // 服务账户就是登录用户、且 npm 默认前缀可写时用默认前缀；否则用登录用户的 ~/.npm-global。
        var target=home??Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        var current=Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
        if(SamePath(target,current)){
            var declared=DefaultPrefix();
            if(declared is not null&&Writable(declared))return declared;
        }
        return string.IsNullOrWhiteSpace(target)?null:Path.Combine(target,".npm-global");
    }
    private static void Trace(string message){
        try{Directory.CreateDirectory(RemoteToolPaths.CodexManagedTools);File.AppendAllText(Path.Combine(RemoteToolPaths.CodexManagedTools,"install.log"),$"{DateTime.Now:O} {message}{Environment.NewLine}");}catch(Exception e)when(e is IOException or UnauthorizedAccessException){}
    }
    private static bool SamePath(string a,string b)=>string.Equals(Path.GetFullPath(a).TrimEnd(Path.DirectorySeparatorChar),Path.GetFullPath(b).TrimEnd(Path.DirectorySeparatorChar),StringComparison.Ordinal);
    private static bool Writable(string directory){
        try{Directory.CreateDirectory(directory);var probe=Path.Combine(directory,".write-"+Guid.NewGuid().ToString("N"));File.WriteAllText(probe,"");File.Delete(probe);return true;}
        catch(Exception e)when(e is IOException or UnauthorizedAccessException){return false;}
    }
    private static string? DefaultPrefix(){
        try{
            var start=new ProcessStartInfo(OperatingSystem.IsWindows()?"cmd.exe":"npm"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
            if(OperatingSystem.IsWindows()){start.ArgumentList.Add("/c");start.ArgumentList.Add("npm");}
            start.ArgumentList.Add("config");start.ArgumentList.Add("get");start.ArgumentList.Add("prefix");
            using var p=Process.Start(start);if(p is null)return null;
            var text=p.StandardOutput.ReadToEnd();p.WaitForExit(10000);
            var value=text.Trim();return p.ExitCode==0&&value.Length>0?value:null;
        }catch(Exception e)when(e is IOException or System.ComponentModel.Win32Exception){return null;}
    }
    /// <summary>当前登录用户的主目录：Windows 取控制台会话账户的 ProfileImagePath，Unix 取 who 里的终端用户。</summary>
    private static string? ConsoleUserHome(){
        try{
            if(OperatingSystem.IsWindows()){
                var name=ConsoleUserName();if(string.IsNullOrWhiteSpace(name))return null;
                using var list=Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows NT\CurrentVersion\ProfileList");
                foreach(var sid in list?.GetSubKeyNames()??Array.Empty<string>()){
                    using var entry=list!.OpenSubKey(sid);
                    if(entry?.GetValue("ProfileImagePath") is string profile&&
                       string.Equals(Path.GetFileName(profile.TrimEnd(Path.DirectorySeparatorChar)),name,StringComparison.OrdinalIgnoreCase))
                        return profile;
                }
                var fallback=Path.Combine(Directory.GetParent(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile))?.FullName??@"C:\Users",name);
                return Directory.Exists(fallback)?fallback:null;
            }
            var user=UnixConsoleUser();if(string.IsNullOrWhiteSpace(user))return null;
            if(OperatingSystem.IsMacOS()){var mac=Path.Combine("/Users",user);return Directory.Exists(mac)?mac:null;}
            foreach(var line in File.ReadLines("/etc/passwd")){
                var parts=line.Split(':');
                if(parts.Length>5&&parts[0]==user)return parts[5];
            }
            var linux=Path.Combine("/home",user);return Directory.Exists(linux)?linux:null;
        }catch(Exception e)when(e is IOException or UnauthorizedAccessException or System.Security.SecurityException or PlatformNotSupportedException){return null;}
    }
    private static string? UnixConsoleUser(){
        try{
            var start=new ProcessStartInfo("who"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true};
            using var p=Process.Start(start);if(p is null)return null;
            var text=p.StandardOutput.ReadToEnd();p.WaitForExit(5000);
            foreach(var line in text.Split('\n',StringSplitOptions.RemoveEmptyEntries)){
                var name=line.Split(' ',StringSplitOptions.RemoveEmptyEntries)[0];
                if(name.Length>0&&name!="root")return name;
            }
        }catch(Exception e)when(e is IOException or System.ComponentModel.Win32Exception){}
        return null;
    }
    /// <summary>控制台会话的用户名（服务以别的账户运行时，Environment.UserName 指的不是登录用户）。</summary>
    private static string? ConsoleUserName(){
        try{
            const int WTSUserName=5;
            var session=WTSGetActiveConsoleSessionId();
            if(session!=0xFFFFFFFF&&WTSQuerySessionInformation(IntPtr.Zero,session,WTSUserName,out var buffer,out _)){
                try{var name=Marshal.PtrToStringUni(buffer);if(!string.IsNullOrWhiteSpace(name))return name;}finally{WTSFreeMemory(buffer);}
            }
        }catch(Exception e)when(e is EntryPointNotFoundException or DllNotFoundException){}
        // 服务账户查不到会话时，用系统记录的最后登录用户（LogonUI）。
        try{
            using var key=Microsoft.Win32.Registry.LocalMachine.OpenSubKey(@"SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\LogonUI");
            if(key?.GetValue("LastLoggedOnUser") is string loggedOn&&loggedOn.Length>0)return loggedOn.Contains('\\')?loggedOn.Split('\\').Last():loggedOn;
        }catch(Exception e)when(e is IOException or UnauthorizedAccessException or System.Security.SecurityException){}
        return null;
    }
    [DllImport("kernel32.dll")]private static extern int WTSGetActiveConsoleSessionId();
    [DllImport("wtsapi32.dll",SetLastError=true,CharSet=CharSet.Unicode)]private static extern bool WTSQuerySessionInformation(IntPtr server,int session,int infoClass,out IntPtr buffer,out int bytes);
    [DllImport("wtsapi32.dll")]private static extern void WTSFreeMemory(IntPtr memory);
    internal static bool Safe(string root,string file){
        root=Path.GetFullPath(root);file=Path.GetFullPath(file);
        if(!file.StartsWith(root.TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar,StringComparison.Ordinal))return false;
        for(var p=file;p!=null&&p!=root;p=Path.GetDirectoryName(p)){
            try{if((File.GetAttributes(p)&FileAttributes.ReparsePoint)!=0)return false;}catch(FileNotFoundException){}catch(DirectoryNotFoundException){}
        }return true;
    }
    /// <summary>Windows 上是 npm.cmd，需经 cmd.exe 启动；其它平台直接执行。</summary>
    private static string? FindNpm(){
        var names=OperatingSystem.IsWindows()?new[]{"npm.cmd","npm.exe"}:new[]{"npm"};
        var fixedPaths=OperatingSystem.IsWindows()
            ?new[]{Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),"nodejs"),Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Programs","nodejs")}
            :new[]{"/usr/bin","/usr/local/bin","/opt/homebrew/bin"};
        foreach(var directory in fixedPaths)foreach(var name in names){
            var candidate=Path.Combine(directory,name);if(File.Exists(candidate))return candidate;}
        foreach(var directory in (Environment.GetEnvironmentVariable("PATH")??"").Split(Path.PathSeparator).Where(p=>p.Length>0))
            foreach(var name in names){var candidate=Path.Combine(directory,name);if(File.Exists(candidate))return candidate;}
        return null;
    }
    private static async Task<Output> RunProcess(string file,string[] args,CancellationToken ct){
        ct.ThrowIfCancellationRequested();
        var script=file.EndsWith(".cmd",StringComparison.OrdinalIgnoreCase)||file.EndsWith(".bat",StringComparison.OrdinalIgnoreCase);
        var start=new ProcessStartInfo(script?"cmd.exe":file){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true};
        if(script){start.ArgumentList.Add("/c");start.ArgumentList.Add(file);}
        foreach(var arg in args)start.ArgumentList.Add(arg);
        using var p=Process.Start(start)??throw new IOException("无法启动安装器");p.StandardInput.Close();
        var output=ReadBounded(p.StandardOutput);var errors=ReadBounded(p.StandardError);
        try{await p.WaitForExitAsync(ct);}catch{if(!p.HasExited)p.Kill(true);throw;}
        var text=await output;var error=await errors;return new(p.ExitCode,text.Length>0?text:error);
    }
    private static async Task<string> ReadBounded(TextReader reader){var builder=new StringBuilder();var buffer=new char[2048];int n;while((n=await reader.ReadAsync(buffer))>0){if(builder.Length<65536)builder.Append(buffer,0,Math.Min(n,65536-builder.Length));}return builder.ToString();}
}
