using System.Diagnostics;
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
    private readonly Func<string,string[],CancellationToken,Task<Output>> run;
    private readonly CancellationToken stopping;
    private static readonly JsonSerializerOptions Json=new(){PropertyNameCaseInsensitive=true};
    private readonly object gate=new();
    private Status status=new("","idle","");
    private Task completion=Task.CompletedTask;
    public bool CanInstall=>npm!=null;
    public Status Current {get{lock(gate)return status;}}
    internal Task Completion=>completion;
    public CodexInstaller(IHostApplicationLifetime lifetime):this(RemoteToolPaths.CodexManagedTools,FindNpm(),RunProcess,lifetime.ApplicationStopping){}
    internal CodexInstaller(string root,string? npm,Func<string,string[],CancellationToken,Task<Output>> run,CancellationToken stopping=default){
        this.root=Path.GetFullPath(root);this.npm=npm;this.run=run;this.stopping=stopping;
        try{var file=Path.Combine(root,"status.json");if(File.Exists(file)){
            status=JsonSerializer.Deserialize<Status>(File.ReadAllText(file),Json)??status;
            if(status.Busy)status=status with{Phase="failed",Message="服务已重启，安装结果未确认。请重新检测后再试。"};
        }}catch(Exception e)when(e is IOException or JsonException){}
    }
    public Status Start(){
        lock(gate){
            if(status.Busy)return status;
            if(npm==null)throw new InvalidOperationException("此平台未提供自动安装，或服务账户无法使用 npm。请按手动步骤安装。 ");
            status=new(Guid.NewGuid().ToString("N"),"installing","正在用 npm 把官方 Codex CLI 安装到系统默认目录；不会更改登录凭据。 ");
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
            var install=await run(npm!,["install","--global","--registry","https://registry.npmjs.org","--no-fund","--no-audit","@openai/codex@latest"],timeout.Token);
            if(install.ExitCode!=0)throw new IOException(Permission(install.Text)?"npm 没有写入系统全局目录的权限（请用管理员身份或可写的全局前缀重试，或按手动步骤安装）":"安装未完成");
            var rootResult=await run(npm!,["root","--global"],timeout.Token);
            var globalRoot=Last(rootResult.Text);
            if(rootResult.ExitCode!=0||globalRoot.Length==0)throw new IOException("无法确定 npm 全局目录");
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
    private static bool Permission(string text)=>text.Contains("EACCES",StringComparison.OrdinalIgnoreCase)||text.Contains("permission denied",StringComparison.OrdinalIgnoreCase);
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
