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
    public CodexInstaller(IHostApplicationLifetime lifetime):this(RemoteToolPaths.CodexManagedTools,
        OperatingSystem.IsLinux()||OperatingSystem.IsMacOS()?FindNpm():null,RunProcess,lifetime.ApplicationStopping){}
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
            status=new(Guid.NewGuid().ToString("N"),"installing","正在从官方 npm 仓库下载到独立目录；不会更改登录凭据。 ");
            var job=status.Id;completion=Task.Run(()=>Install(job));return status;
        }
    }
    private void Update(Status value){
        lock(gate){status=value;try{Atomic(Path.Combine(root,"status.json"),Obj(("Id",value.Id),("Phase",value.Phase),("Message",value.Message),("Version",value.Version)));}catch(IOException){}}
    }
    private async Task Install(string id){
        var prefix=Path.Combine(root,"runtime-"+id);var promoted=false;
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(stopping);timeout.CancelAfter(TimeSpan.FromMinutes(5));
        try{
            Directory.CreateDirectory(prefix);
            if(!OperatingSystem.IsWindows())File.SetUnixFileMode(prefix,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
            var result=await run(npm!,["install","--prefix",prefix,"--cache",Path.Combine(prefix,"npm-cache"),"--registry","https://registry.npmjs.org","--ignore-scripts","--include=optional","--omit=dev","--no-audit","--no-fund","--loglevel=error","@openai/codex@latest"],timeout.Token);
            if(result.ExitCode!=0)throw new IOException("安装未完成");
            Update(new(id,"validating","正在验证 Codex 程序和当前应用所需协议。"));
            var options=new EnumerationOptions{RecurseSubdirectories=true,AttributesToSkip=FileAttributes.ReparsePoint};
            var binary=Directory.EnumerateFiles(prefix,"codex",options).Where(p=>new FileInfo(p).Length>1_000_000&&Safe(prefix,p)).FirstOrDefault();
            if(binary==null||!CodexExecutable.Complete(binary))throw new IOException("未找到可执行程序");
            var version=await run(binary,["--version"],timeout.Token);
            var match=Regex.Match(version.Text,@"codex-cli\s+([0-9][0-9A-Za-z.\-+]*)");
            if(version.ExitCode!=0||!match.Success)throw new IOException("程序版本验证失败");
            var schema=Path.Combine(prefix,"verified-schema");
            var validation=await run(binary,["app-server","generate-json-schema","--out",schema],timeout.Token);
            if(validation.ExitCode!=0||!new[]{"v2/ThreadStartParams.json","v2/ThreadTurnsListParams.json","v2/TurnStartParams.json"}.All(p=>File.Exists(Path.Combine(schema,p))))
                throw new IOException("该版本缺少所需接口");
            Atomic(Path.Combine(root,"executable.json"),Obj(("executable",binary),("version",match.Groups[1].Value)));
            promoted=true;Update(new(id,"ready","安装完成。请重新检测，再登录账户或配置 Provider。",match.Groups[1].Value));
        }catch(Exception e)when(e is IOException or UnauthorizedAccessException or OperationCanceledException or InvalidOperationException or System.ComponentModel.Win32Exception){
            Update(new(id,"failed","安装未完成或协议不兼容。请检查服务端联网、npm 与目录权限，或按手动步骤安装受支持版本。"));
        }finally{
            if(!promoted&&Safe(root,prefix)&&Directory.Exists(prefix)&&(File.GetAttributes(prefix)&FileAttributes.ReparsePoint)==0)
                try{Directory.Delete(prefix,true);}catch(IOException){}catch(UnauthorizedAccessException){}
        }
    }
    internal static bool Safe(string root,string file){
        root=Path.GetFullPath(root);file=Path.GetFullPath(file);
        if(!file.StartsWith(root.TrimEnd(Path.DirectorySeparatorChar)+Path.DirectorySeparatorChar,StringComparison.Ordinal))return false;
        for(var p=file;p!=null&&p!=root;p=Path.GetDirectoryName(p)){
            try{if((File.GetAttributes(p)&FileAttributes.ReparsePoint)!=0)return false;}catch(FileNotFoundException){}catch(DirectoryNotFoundException){}
        }return true;
    }
    private static string? FindNpm()=>new[]{"/usr/bin/npm","/usr/local/bin/npm","/opt/homebrew/bin/npm"}
        .Concat((Environment.GetEnvironmentVariable("PATH")??"").Split(Path.PathSeparator).Where(p=>p.Length>0).Select(p=>Path.Combine(p,"npm"))).FirstOrDefault(File.Exists);
    private static async Task<Output> RunProcess(string file,string[] args,CancellationToken ct){
        ct.ThrowIfCancellationRequested();
        var start=new ProcessStartInfo(file){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true};
        foreach(var arg in args)start.ArgumentList.Add(arg);
        using var p=Process.Start(start)??throw new IOException("无法启动安装器");p.StandardInput.Close();
        var output=ReadBounded(p.StandardOutput);var errors=ReadBounded(p.StandardError);
        try{await p.WaitForExitAsync(ct);}catch{if(!p.HasExited)p.Kill(true);throw;}
        var text=await output;await errors;return new(p.ExitCode,text);
    }
    private static async Task<string> ReadBounded(TextReader reader){var builder=new StringBuilder();var buffer=new char[2048];int n;while((n=await reader.ReadAsync(buffer))>0){if(builder.Length<65536)builder.Append(buffer,0,Math.Min(n,65536-builder.Length));}return builder.ToString();}
}
