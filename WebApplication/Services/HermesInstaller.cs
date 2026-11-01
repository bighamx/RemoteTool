using System.Diagnostics;
using System.Text;
using System.Text.Json;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services;

/// <summary>
/// 安装官方 Hermes（PyPI hermes-agent）到 RemoteTool 期望的位置：
/// &lt;source&gt;/venv 下的独立虚拟环境。只用固定包名与官方索引，不碰账号和密钥。
/// uv 存在时优先用 uv（无需 apt 的 python3-venv）；否则退回 python3 -m venv。
/// </summary>
public sealed class HermesInstaller
{
    public sealed record Status(string Id,string Phase,string Message,string Version="") { public bool Busy=>Phase is "installing" or "validating"; }
    internal sealed record Output(int ExitCode,string Text);
    private static readonly JsonSerializerOptions Json=new(){PropertyNameCaseInsensitive=true};
    private static readonly TimeSpan Limit=TimeSpan.FromMinutes(20);
    private readonly string root,source;
    private readonly string? uv;
    private readonly string? python;
    private readonly Func<string,string[],CancellationToken,Task<Output>> run;
    private readonly CancellationToken stopping;
    private readonly object gate=new();
    private Status status=new("","idle","");
    private Task completion=Task.CompletedTask;
    public bool CanInstall=>uv!=null||python!=null;
    public string Venv=>Path.Combine(source,"venv");
    public Status Current {get{lock(gate)return status;}}
    internal Task Completion=>completion;
    public HermesInstaller(IHostApplicationLifetime lifetime,IConfiguration configuration,HermesProfiles profiles)
        :this(RemoteToolPaths.HermesManagedTools,SourceDirectory(configuration,profiles),FindUv(),FindPython(),RunProcess,lifetime.ApplicationStopping){}
    internal HermesInstaller(string root,string source,string? uv,Func<string,string[],CancellationToken,Task<Output>> run,CancellationToken stopping=default)
        :this(root,source,uv,null,run,stopping){}
    internal HermesInstaller(string root,string source,string? uv,string? python,Func<string,string[],CancellationToken,Task<Output>> run,CancellationToken stopping=default){
        this.root=Path.GetFullPath(root);this.source=Path.GetFullPath(source);this.uv=uv;this.python=python;this.run=run;this.stopping=stopping;
        try{var file=Path.Combine(root,"status.json");if(File.Exists(file)){
            status=JsonSerializer.Deserialize<Status>(File.ReadAllText(file),Json)??status;
            if(status.Busy)status=status with{Phase="failed",Message="服务已重启，安装结果未确认。请重新检测后再试。"};
        }}catch(Exception e)when(e is IOException or JsonException){}
    }
    private static string SourceDirectory(IConfiguration configuration,HermesProfiles profiles){
        var configured=configuration["Hermes:SourceDirectory"];
        return string.IsNullOrWhiteSpace(configured)?Path.Combine(profiles.Root,"hermes-agent"):configured;
    }
    public Status Start(){
        lock(gate){
            if(status.Busy)return status;
            if(!CanInstall)throw new InvalidOperationException("当前平台缺少 uv 与可用的 Python，无法自动安装。");
            status=new(Guid.NewGuid().ToString("N"),"installing",$"正在安装官方 Hermes 到 {Venv}；不会改动账号或密钥。 ");
            var job=status.Id;completion=Task.Run(()=>Install(job));return status;
        }
    }
    private void Update(Status value){
        lock(gate){status=value;try{Atomic(Path.Combine(root,"status.json"),Obj(("Id",value.Id),("Phase",value.Phase),("Message",value.Message),("Version",value.Version)));}catch(IOException){}}}
    private async Task Install(string id){
        using var timeout=CancellationTokenSource.CreateLinkedTokenSource(stopping);timeout.CancelAfter(Limit);
        var python=Path.Combine(Venv,OperatingSystem.IsWindows()?"Scripts/python.exe":"bin/python");
        try{
            Directory.CreateDirectory(source);
            var env=OperatingSystem.IsWindows()?"Scripts":"bin";
            if(uv is not null){
                var create=await run(uv,["venv","--python","3.12",Venv],timeout.Token);
                if(create.ExitCode!=0||!File.Exists(python))throw new IOException("创建虚拟环境失败");
                Update(new(id,"validating","正在从官方索引安装 hermes-agent。"));
                // messaging extra 带 aiohttp：API Server 适配器缺它会拒绝启动。
                var install=await run(uv,["pip","install","--python",python,"hermes-agent[messaging]"],timeout.Token);
                if(install.ExitCode!=0)throw new IOException("安装未完成");
            }else{
                var create=await run(python!,["-m","venv",Venv],timeout.Token);
                if(create.ExitCode!=0||!File.Exists(python))throw new IOException("创建虚拟环境失败（需要 python3-venv）");
                Update(new(id,"validating","正在从官方索引安装 hermes-agent。"));
                var install=await run(python,["-m","pip","install","--disable-pip-version-check","--no-input","hermes-agent[messaging]"],timeout.Token);
                if(install.ExitCode!=0)throw new IOException("安装未完成");
            }
            var verify=await run(python,["-c","import importlib.metadata as m, hermes_bootstrap, aiohttp; print(m.version('hermes-agent'))"],timeout.Token);
            if(verify.ExitCode!=0)throw new IOException("安装后无法导入 hermes_bootstrap 或 aiohttp");
            var version=verify.Text.Trim().Split('\n',StringSplitOptions.RemoveEmptyEntries).LastOrDefault()??"";
            Atomic(Path.Combine(root,"install.json"),Obj(("python",python),("venv",Venv),("version",version),("source","pypi")));
            Update(new(id,"ready",$"已安装 Hermes {version}。请继续配置模型 Provider 与账号，然后重新检测。",version));
        }catch(Exception e)when(e is IOException or UnauthorizedAccessException or OperationCanceledException or InvalidOperationException or System.ComponentModel.Win32Exception){
            var hint=e.Message.Contains("python3-venv")?"缺少 python3-venv：请安装 uv 或执行 sudo apt install python3.12-venv，然后重试。":"安装未完成。请检查服务端联网与目录权限后重试，或按手动步骤安装。";
            Update(new(id,"failed",hint));
        }
    }
    /// <summary>没有 uv 时的回退启动器：Unix 用 python3，Windows 用 python/py。</summary>
    private static string? FindPython(){
        var names=OperatingSystem.IsWindows()?new[]{"python.exe","py.exe","python3.exe"}:new[]{"python3","python"};
        foreach(var directory in (Environment.GetEnvironmentVariable("PATH")??"").Split(Path.PathSeparator).Where(p=>p.Length>0))
            foreach(var name in names){var candidate=Path.Combine(directory,name);if(File.Exists(candidate))return candidate;}
        var fixedPaths=OperatingSystem.IsWindows()
            ?new[]{Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Programs","Python"),@"C:\Python312",@"C:\Python313"}
            :new[]{"/usr/bin","/usr/local/bin","/opt/homebrew/bin"};
        foreach(var directory in fixedPaths)foreach(var name in names){
            var candidate=Path.Combine(directory,name);if(File.Exists(candidate))return candidate;}
        return null;
    }
    private static string? FindUv(){
        var candidates=new List<string>{
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),".local","bin",OperatingSystem.IsWindows()?"uv.exe":"uv"),
            "/usr/local/bin/uv","/opt/homebrew/bin/uv",Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"Programs","uv","uv.exe"),
        };
        candidates.AddRange((Environment.GetEnvironmentVariable("PATH")??"").Split(Path.PathSeparator)
            .Where(p=>p.Length>0).Select(p=>Path.Combine(p,OperatingSystem.IsWindows()?"uv.exe":"uv")));
        return candidates.FirstOrDefault(File.Exists);
    }
    private static async Task<Output> RunProcess(string file,string[] args,CancellationToken ct){
        ct.ThrowIfCancellationRequested();
        var start=new ProcessStartInfo(file){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true};
        foreach(var arg in args)start.ArgumentList.Add(arg);
        using var p=Process.Start(start)??throw new IOException("无法启动安装器");p.StandardInput.Close();
        var output=Read(p.StandardOutput);var errors=Read(p.StandardError);
        try{await p.WaitForExitAsync(ct);}catch{if(!p.HasExited)p.Kill(true);throw;}
        var text=await output;var error=await errors;return new(p.ExitCode,text.Length>0?text:error);
    }
    private static async Task<string> Read(StreamReader reader){var builder=new StringBuilder();var buffer=new char[2048];int n;while((n=await reader.ReadAsync(buffer))>0){if(builder.Length<65536)builder.Append(buffer,0,Math.Min(n,65536-builder.Length));}return builder.ToString();}
}
