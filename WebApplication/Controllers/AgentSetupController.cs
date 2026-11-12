using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.Codex;
using RemoteTool.WebApi.Services.RemoteControl;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Controllers;

[Authorize,ApiController,Route("api/agent-setup")]
public sealed class AgentSetupController(IConfiguration configuration,HermesProfiles profiles,HermesManagement management,HermesGatewayAdmin gateway,HermesBridge bridge,CodexInstaller installer,HermesInstaller hermesInstaller,ClaudeBridge claude):ControllerBase
{
    [HttpGet("{agent}")]
    public async Task<IActionResult> Status(string agent,[FromQuery]string profile="default",CancellationToken ct=default) {
        if(agent=="codex") {
            var current=installer.Current;
            if(current.Busy)return Ok(Guide(agent,"installing","正在安装 Codex",current.Message,false));
            var home=configuration["Codex:Home"];
            if(string.IsNullOrWhiteSpace(home))try {
                var p=Path.Combine(RemoteToolPaths.CodexBridge,"connection.json");
                if(System.IO.File.Exists(p))home=JsonNode.Parse(System.IO.File.ReadAllText(p))?["home"]?.ToString();
            }catch(Exception e)when(e is IOException or JsonException){}
            home=string.IsNullOrWhiteSpace(home)?Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),".codex"):home;
            try { CodexExecutable.Resolve(configuration["Codex:Executable"]??"",home); }
            catch(Exception e)when(e is CodexError or IOException or UnauthorizedAccessException){
                var note=installer.CanInstall?"可在下方直接安装经过协议校验的官方 Codex CLI；安装到独立目录，不会改动现有登录。":"未找到完整、可执行的 Codex。请在服务端安装 CLI 或修正程序路径后重新检测。";
                if(current.Phase=="failed"&&current.Message.Length>0)note=current.Message;
                return Ok(Guide(agent,"not_installed","Codex 尚未就绪",note,false,installer.CanInstall));}
            var auth=System.IO.File.Exists(Path.Combine(home,"auth.json"))||!string.IsNullOrWhiteSpace(Environment.GetEnvironmentVariable("OPENAI_API_KEY"));
            if(!auth)try {
                var providersPath=Path.Combine(RemoteToolPaths.CodexState,"providers.json");
                if(System.IO.File.Exists(providersPath)&&JsonNode.Parse(System.IO.File.ReadAllText(providersPath)) is JsonObject providerMap)
                    auth=providerMap.Any(p=>p.Value is JsonObject detail&&!string.IsNullOrWhiteSpace(detail["api_key"]?.ToString()));
            }catch(Exception e)when(e is IOException or JsonException){}
            return Ok(auth?Guide(agent,"ready","Codex 已找到","",false):Guide(agent,"sign_in","需要登录或配置模型","请在服务端登录 Codex 官方账号，或在模型设置中配置第三方 Provider。",false));
        }
        if(agent=="claude") {
            if(configuration.GetValue<bool?>("Claude:Enabled")==false)return Ok(ClaudeGuide("disabled","Claude Code 接入未启用","服务端设置了 Claude:Enabled=false。"));
            try {
                using var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct);timeout.CancelAfter(TimeSpan.FromSeconds(20));
                using var response=await claude.SendAsync(HttpMethod.Get,"setup",null,timeout.Token);
                var payload=JsonNode.Parse(await response.Content.ReadAsStringAsync(timeout.Token));
                if(!response.IsSuccessStatusCode)return Ok(ClaudeGuide("not_running","Claude 桥接未就绪",payload?["message"]?.ToString()??"请检查服务端 Claude 桥接。"));
                return Ok(payload?["ready"]?.GetValue<bool>()==true?ClaudeGuide("ready","Claude Code 已登录",""):ClaudeGuide("sign_in","需要登录 Claude Code",payload?["message"]?.ToString()??""));
            }catch(InvalidOperationException e){return Ok(ClaudeGuide("not_installed","Claude Code 尚未就绪",e.Message));}
            catch(Exception e)when(e is HttpRequestException or IOException or OperationCanceledException or JsonException){return Ok(ClaudeGuide("not_running","Claude 桥接未响应","请稍后重新检测。"));}
        }
        if(agent!="hermes")return NotFound();
        if(hermesInstaller.Current.Busy)return Ok(Guide(agent,"installing","正在安装 Hermes",hermesInstaller.Current.Message,false));
        string homePath;
        try{homePath=profiles.HomeFor(profile);}catch(ArgumentException){return Ok(Guide(agent,"profile_missing","Profile 尚未配置","请先在服务端创建并配置此 Profile，或切换到默认 Profile。",false));}
        var source=configuration["Hermes:SourceDirectory"]??Path.Combine(profiles.Root,"hermes-agent");
        var python=configuration["Hermes:PythonExecutable"]??Path.Combine(source,"venv",OperatingSystem.IsWindows()?"Scripts":"bin",OperatingSystem.IsWindows()?"python.exe":"python");
        var helper=Path.Combine(Path.GetDirectoryName(InteractiveProcessLauncher.GetApplicationDllPath())!,"hermes","hermes_management.py");
        var managed=System.IO.File.Exists(python)&&System.IO.File.Exists(helper);
        var hasKey=!string.IsNullOrWhiteSpace(profiles.ApiKey(profile));
        if(hasKey) {
            using var timeout=CancellationTokenSource.CreateLinkedTokenSource(ct);timeout.CancelAfter(TimeSpan.FromSeconds(4));
            using var entered=HermesProfileScope.Enter(profile);
            try {
                using var response=await bridge.SendAsync(HttpMethod.Get,"v1/capabilities",null,null,timeout.Token);
                if(response.IsSuccessStatusCode) {
                    var payload=JsonNode.Parse(await response.Content.ReadAsStringAsync(timeout.Token));
                    if(payload?["features"]?["run_submission"]?.ToString()=="true")return Ok(Guide(agent,"ready","Hermes API 已连接","",false));
                    return Ok(Guide(agent,"unsupported_version","Hermes 对话能力尚未就绪","API 已连接，但未提供当前应用需要的对话接口。请更新 Hermes 并启用应用通道，再重新检测。",false));
                }
                if(response.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden)
                    return Ok(Guide(agent,"credentials_invalid","Hermes API 接入需要更新","服务已响应，但接入密钥不匹配。请核对服务端 Hermes:KeyFile 与网关配置，保留原有数据。",false));
            }catch(Exception e)when(e is HttpRequestException or IOException or InvalidOperationException or OperationCanceledException){}
        }
        var enabled=false;
        var env=Path.Combine(homePath,".env");
        if(System.IO.File.Exists(env))foreach(var line in System.IO.File.ReadLines(env)){
            var p=line.Split('=',2);if(p.Length==2&&p[0].Trim()=="API_SERVER_ENABLED")enabled=new[]{"true","1","yes","on"}.Contains(p[1].Trim().Trim('"','\'').ToLowerInvariant());
        }
        if(!hasKey||!enabled)return Ok(Guide(agent,managed?"configure":"not_installed",managed?"启用 Hermes API":"Hermes 尚未接入",
            managed?"本地 API 尚未启用或缺少接入密钥。可写入配置并安全重启网关，现有任务结束后生效。"
                :hermesInstaller.CanInstall?"服务端还没有 Hermes：可在下方一键安装官方 hermes-agent 到独立虚拟环境，装完再配置模型与账号。"
                :"服务端缺少 Hermes API 接入或管理组件。请先安装并完成初始设置。",managed&&profile=="default"));
        return Ok(Guide(agent,"not_running","Hermes API 未连接","配置已找到，但本地 API 未响应。请启动网关，或使用安全重启后重新检测。",managed&&profile=="default"));
    }
    private static object ClaudeGuide(string state,string title,string message)=>new{
        agent="claude",state,title,message,canConfigureApi=false,canInstall=false,install=(object)null,
        installationNote="RemoteTool 使用官方 Claude Agent SDK 驱动本机 Claude Code；不会代为登录、复制凭据或修改你的 Claude 配置。检测只读取安装与登录状态，不发送提示词。",
        steps=new[]{"在运行 RemoteTool 的系统用户下安装官方 Claude Code（claude 命令）和 Node.js 20+。","在该用户下执行 claude auth login，并用 claude auth status 确认已登录。","在服务端 claude 目录（源码为 WebApplication/Tools/ClaudeBridge）执行 npm ci --omit=dev --omit=optional。","如程序不在默认位置，配置 Claude:Executable / Claude:NodeExecutable 的绝对路径。","返回这里重新检测。"}};
    private object Guide(string agent,string state,string title,string message,bool canConfigure,bool? canInstall=null){
        var installable=canInstall??(agent=="codex"?installer.CanInstall:hermesInstaller.CanInstall);
        return new {
        agent,state,title,message,canConfigureApi=canConfigure,
        canInstall=installable,
        install=agent=="codex"
            ?new{phase=installer.Current.Phase,message=installer.Current.Message,version=installer.Current.Version,busy=installer.Current.Busy,target=installer.Target}
            :new{phase=hermesInstaller.Current.Phase,message=hermesInstaller.Current.Message,version=hermesInstaller.Current.Version,busy=hermesInstaller.Current.Busy,target=hermesInstaller.Venv},
        installationNote=!installable
            ?"当前部署未提供受验证的自动安装器；安装可能涉及系统依赖和账号登录，请在服务端完成。检测本身不会安装软件或改动配置。"
            :agent=="codex"
                ?"会用固定官方 npm 源安装到登录用户的 npm 全局目录，并校验程序版本与当前应用需要的协议；不会自动登录或改动现有账号。不满足条件时仍按下面步骤手动安装。"
                :"会用官方 PyPI 包 hermes-agent 安装到服务端独立虚拟环境，并校验可导入；不会改动账号、密钥或现有配置。安装后仍需配置模型 Provider，可用下方一键配置启用本地 API。",
        steps=agent=="codex"?new[]{"在服务端安装 Codex CLI，并确保运行 RemoteTool 的用户可执行。","在该用户下完成 Codex 登录，或配置第三方 Provider。","在服务端配置 Codex:Executable / Codex:Home；Linux systemd 服务需要单独配置网络代理。","返回这里重新检测。"}
            :new[]{"在服务端安装 Hermes，并完成模型提供方和账号设置。","配置 Hermes:HomeDirectory、Hermes:SourceDirectory 和 Hermes:PythonExecutable。","核对 Hermes:BaseUrl 与本地 API 的监听地址和端口；默认 http://127.0.0.1:8642/。","启用本地 API；可用时使用下方的一键配置。","等待网关安全重启后重新检测。"}
        };
    }
    [HttpPost("codex/install")]
    public IActionResult InstallCodex(){
        if(!installer.CanInstall)return Conflict(new{message="当前平台或服务账户不支持自动安装，请按步骤在服务端手动安装。"});
        try{
            var status=installer.Start();
            return Ok(new{started=true,phase=status.Phase,message=status.Message});
        }catch(InvalidOperationException){return Conflict(new{message="安装器不可用，请稍后重试或按步骤手动安装。"});}
    }
    [HttpPost("hermes/install")]
    public IActionResult InstallHermes(){
        if(!hermesInstaller.CanInstall)return Conflict(new{message="当前平台缺少 uv 与 python3-venv，无法自动安装，请按步骤手动安装。"});
        try{
            var status=hermesInstaller.Start();
            return Ok(new{started=true,phase=status.Phase,message=status.Message});
        }catch(InvalidOperationException){return Conflict(new{message="安装器不可用，请稍后重试或按步骤手动安装。"});}
    }
    [HttpPost("hermes/configure")]
    public async Task<IActionResult> Configure(CancellationToken ct){
        using var scope=HermesProfileScope.Enter("default");
        try{
            var result=await management.EnableApiServer(ct);
            var maintenance=await gateway.Start(true,ct);
            return Ok(new{configured=true,message="配置已写入，网关将等待当前任务结束后安全重启。请稍后重新检测。",maintenance});
        }catch(Exception e)when(e is InvalidOperationException or IOException){return Conflict(new{message="无法自动配置，请检查 Hermes 管理组件和运行环境。可按步骤在服务端手动完成。"});}
    }
}
