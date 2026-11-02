using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Xml;
using RemoteTool.Updates;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Services.Updates;

public interface IUpdaterBootstrapHost
{
    string Kind { get; }
    bool IsElevated { get; }
    XmlDocument ReadIis();
    SystemdService ReadSystemd(string unit, bool user);
    void Protect(string path);
    void RemoveLegacyTask(string backupPath);
    UpdaterProcessIdentity Launch(string assembly, string configPath);
}

/// <summary>Provision the bundled engine using the permissions already held by IIS.</summary>
public sealed class UpdaterBootstrap(IWebHostEnvironment environment, IConfiguration configuration, DeviceIdentity identity, IUpdaterBootstrapHost host = null)
{
    private readonly IUpdaterBootstrapHost platform = host ?? new PlatformHost();
    private readonly SemaphoreSlim gate = new(1, 1);
    private readonly SemaphoreSlim launches = new(1, 1);
    private readonly string root = Path.Combine(RemoteToolPaths.ProgramData, "updates");
    public string Message { get; private set; } = "正在准备独立更新器";
    public async Task Ensure(CancellationToken ct) {
        await gate.WaitAsync(ct);
        try {
            var installation = Path.Combine(root, "installation.json");
            if (File.Exists(installation)) {
                var existing = UpdateContract.Read<Installation>(installation);
                var currentAssembly = Path.Combine(environment.ContentRootPath, "WebApplication.dll");
                if (existing.UpdaterVersion == UpdateContract.UpdaterVersion && File.Exists(currentAssembly) && File.Exists(existing.UpdaterAssembly) &&
                    SHA256.HashData(File.ReadAllBytes(currentAssembly)).SequenceEqual(SHA256.HashData(File.ReadAllBytes(existing.UpdaterAssembly)))) { Message = null; return; }
                var jobFile = Path.Combine(root, "job.json");
                if (File.Exists(jobFile) && UpdateContract.Read<UpdateJob>(jobFile).Phase is "requested" or "downloading" or "waiting" or "pulling" or "building" or "switching" or "verifying" or "rolling_back" or "migrating" or "recovery_required") {
                    Message = "当前更新任务结束后再升级独立更新器"; return;
                }
                if (platform.Kind == "unsupported" || !platform.IsElevated) { Message = "更新器准备需要服务端现有权限"; return; }
                if (existing.DeviceId != identity.Id) throw new InvalidOperationException("现有更新器属于另一设备，拒绝覆盖");
                var upgradedEngine = await CopyEngine(environment.ContentRootPath, ct);
                var upgradedAssembly = Path.Combine(upgradedEngine, "WebApplication.dll");
                platform.RemoveLegacyTask(Path.Combine(root, "legacy-updater-task.xml"));
                UpdateContract.Write(installation, existing with { UpdaterVersion = UpdateContract.UpdaterVersion, UpdaterAssembly = upgradedAssembly });
                Message = null; return;
            }
            if (platform.Kind == "unsupported") { Message = "自动更新暂支持 Windows IIS / Linux systemd"; return; }
            if (!configuration.GetValue("ServerUpdate:AutoInstall", true)) { Message = "自动安装更新器已关闭"; return; }
            if (!platform.IsElevated) {
                Message = "服务端权限不足，需管理员安装一次更新器"; return;
            }
            var directory = Path.GetFullPath(environment.ContentRootPath);
            if (platform.Kind == "systemd") { await PrepareLinux(directory, installation, ct); return; }
            var xml = platform.ReadIis();
            XmlElement site = null; string pool = null;
            foreach (XmlElement candidate in xml.SelectNodes("/configuration/system.applicationHost/sites/site")!) {
                var application = (XmlElement)candidate.SelectSingleNode("application[@path='/']");
                var vdir = (XmlElement)application?.SelectSingleNode("virtualDirectory[@path='/']");
                if (vdir == null || !Path.GetFullPath(Environment.ExpandEnvironmentVariables(vdir.GetAttribute("physicalPath"))).TrimEnd('\\').Equals(directory.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)) continue;
                site = candidate; pool = application.GetAttribute("applicationPool"); break;
            }
            if (site == null || string.IsNullOrWhiteSpace(pool)) { Message = "当前不是可识别的 IIS 站点，仅检查更新"; return; }
            if (xml.SelectNodes("/configuration/system.applicationHost/sites/site/application")!.Cast<XmlElement>().Count(a => a.GetAttribute("applicationPool") == pool) != 1) {
                Message = "请为站点使用独立应用池后启用更新器"; return;
            }
            var binding = site.SelectNodes("bindings/binding")!.Cast<XmlElement>().FirstOrDefault(b => b.GetAttribute("protocol") == "http" &&
                b.GetAttribute("bindingInformation").EndsWith(':')) ?? throw new InvalidOperationException("需要可通过本机回环地址访问的 HTTP 绑定");
            var parts = binding.GetAttribute("bindingInformation").Split(':');
            if (!int.TryParse(parts[^2], out var port) || port is < 1 or > 65535) throw new InvalidOperationException("IIS 端口无效");
            string project = null;
            for (var parent = new DirectoryInfo(directory); parent != null; parent = parent.Parent)
                if (File.Exists(Path.Combine(parent.FullName, "WebApplication.csproj"))) { project = parent.FullName; break; }
            var engine = await CopyEngine(directory, ct);
            var shared = Path.Combine(root, "shared"); var releases = Path.Combine(root, "releases");
            Directory.CreateDirectory(shared); Directory.CreateDirectory(releases);
            if (project == null) PrepareShared(directory, shared);
            var value = new Installation(site.GetAttribute("name"), pool, releases, shared, $"http://127.0.0.1:{port}/api/device/update-health", identity.Id,
                Convert.ToHexString(RandomNumberGenerator.GetBytes(32)), project == null ? "release" : "source", project, directory, UpdateContract.UpdaterVersion, Path.Combine(engine, "WebApplication.dll"));
            UpdateContract.Write(installation, value);
            Message = null;
        } catch (Exception error) when (error is not OperationCanceledException || !ct.IsCancellationRequested) {
            Message = error is UnauthorizedAccessException ? "服务端权限不足，需管理员安装一次更新器" : "更新器自动准备未完成：" + error.Message;
        } finally { gate.Release(); }
    }
    private async Task PrepareLinux(string directory, string installation, CancellationToken ct) {
        var unit = configuration["ServerUpdate:ServiceName"] ?? Environment.GetEnvironmentVariable("REMOTETOOL_SERVICE_NAME") ?? "remotetool.service";
        if (!unit.EndsWith(".service", StringComparison.Ordinal)) unit += ".service";
        var user = configuration.GetValue("ServerUpdate:UserService", false);
        var service = platform.ReadSystemd(unit, user);
        if (Path.GetFullPath(service.Directory) != directory || service.Pid != Environment.ProcessId) throw new InvalidOperationException("当前进程与指定 systemd 服务不匹配");
        if (service.KillMode != "process") throw new InvalidOperationException("请将网站 systemd 服务的 KillMode 设为 process，避免停站时结束更新进程");
        var address = (configuration["urls"] ?? Environment.GetEnvironmentVariable("ASPNETCORE_URLS") ?? "http://127.0.0.1:5000").Split(';')[0].Replace("://*","://127.0.0.1").Replace("://+","://127.0.0.1");
        var url = new Uri(address); if (url.Scheme != "http") throw new InvalidOperationException("自动更新需要本机 HTTP 健康检查地址");
        var engine = await CopyEngine(directory, ct);
        string project = null;
        for (var parent = new DirectoryInfo(directory); parent != null; parent = parent.Parent)
            if (File.Exists(Path.Combine(parent.FullName, "WebApplication.csproj"))) { project = parent.FullName; break; }
        UpdateContract.Write(installation, new Installation(unit, unit, Path.Combine(root,"releases"), Path.Combine(root,"shared"),
            $"http://127.0.0.1:{url.Port}/api/device/update-health", identity.Id, Convert.ToHexString(RandomNumberGenerator.GetBytes(32)),
            project == null ? "release" : "source", project, directory, UpdateContract.UpdaterVersion, Path.Combine(engine,"WebApplication.dll"), "systemd", user));
        Message = null;
    }
    public async Task Start(string jobId, CancellationToken ct) {
        await launches.WaitAsync(ct);
        try {
        var path = Path.Combine(root, "installation.json"); var value = UpdateContract.Read<Installation>(path);
        if (!File.Exists(value.UpdaterAssembly)) throw new IOException("外部更新 DLL 不存在，请先检查更新");
        var lockReady=false;
        for(var attempt=0;attempt<300;attempt++) {
            try { using var previous=new FileStream(Path.Combine(root,"worker.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);lockReady=true;break; }
            catch(IOException){await Task.Delay(100,ct);}
        }
        if(!lockReady)throw new IOException("上一个更新进程尚未释放，请稍后重试；网站未停止");
        var accepted=UpdateContract.Read<UpdateJob>(Path.Combine(root,"job.json"));
        if(accepted.Id!=jobId || accepted.Phase is "completed" or "rolled_back" or "failed" or "deferred" or "downloaded")return;
        var process = platform.Launch(value.UpdaterAssembly, path);
        UpdateContract.Write(Path.Combine(root,"jobs",jobId,"launch.json"), process);
        var acknowledged = Path.Combine(root,"jobs",jobId,"worker.json");
        for (var count = 0; count < 100; count++) {
            if (File.Exists(acknowledged)) {
                var state = UpdateContract.Read<JsonObject>(acknowledged);
                if (state["pid"]?.GetValue<int>() == process.Pid && state["startCookie"]?.GetValue<long>() == process.StartCookie) return;
            }
            await Task.Delay(100, ct);
        }
        // An unconfirmed launch is not a failed update; leave the accepted job busy.
        } finally { launches.Release(); }
    }
    public async Task Resume(CancellationToken ct) {
        var settings=Path.Combine(root,"installation.json");var file=Path.Combine(root,"job.json");
        if(!File.Exists(settings)||!File.Exists(file)||UpdateContract.Read<Installation>(settings).UpdaterVersion!=UpdateContract.UpdaterVersion)return;
        var job=UpdateContract.Read<UpdateJob>(file);
        if(job.Phase is not ("requested" or "downloading" or "waiting" or "pulling" or "building" or "switching" or "verifying" or "rolling_back"))return;
        try {using var held=new FileStream(Path.Combine(root,"worker.lock"),FileMode.OpenOrCreate,FileAccess.ReadWrite,FileShare.None);}
        catch(IOException){return;}
        await Start(job.Id,ct);
    }
    private async Task<string> CopyEngine(string directory, CancellationToken ct) {
        var engineDll = Path.Combine(directory, "WebApplication.dll");
        if (new[] { "WebApplication.dll", "WebApplication.deps.json", "WebApplication.runtimeconfig.json" }.Any(name => !File.Exists(Path.Combine(directory, name)))) throw new InvalidOperationException("缺少服务端 DLL 或运行配置，请重新编译或使用完整发布包");
        Directory.CreateDirectory(root); platform.Protect(root);
        var digest = Convert.ToHexString(SHA256.HashData(await File.ReadAllBytesAsync(engineDll, ct)))[..16];
        var engine = Path.Combine(root, "engine", digest); Directory.CreateDirectory(engine);
        // Only executable code and runtime manifests; never copy configuration, DBs,
        // logs, credentials or apphosts into the outside updater directory.
        var runtimes = Path.Combine(directory, "runtimes");
        var platformFiles = Directory.Exists(runtimes) ? Directory.EnumerateFiles(runtimes, "*.dll", new EnumerationOptions { RecurseSubdirectories = true, AttributesToSkip = FileAttributes.ReparsePoint }) : [];
        foreach (var file in Directory.GetFiles(directory, "*.dll").Concat(platformFiles).Concat(new[] { Path.Combine(directory, "WebApplication.deps.json"), Path.Combine(directory, "WebApplication.runtimeconfig.json") })) {
            var target = Path.Combine(engine, Path.GetRelativePath(directory, file));
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            if (File.Exists(target) && !SHA256.HashData(File.ReadAllBytes(target)).SequenceEqual(SHA256.HashData(File.ReadAllBytes(file)))) throw new IOException("更新器副本不一致，请核对本机文件");
            if (!File.Exists(target)) File.Copy(file, target);
        }
        return engine;
    }
    private sealed class PlatformHost : IUpdaterBootstrapHost
    {
        public string Kind => OperatingSystem.IsWindows() ? "iis" : OperatingSystem.IsLinux() ? "systemd" : "unsupported";
        public bool IsElevated { get { if (!OperatingSystem.IsWindows()) return OperatingSystem.IsLinux(); using var current = WindowsIdentity.GetCurrent(); return current.IsSystem || new WindowsPrincipal(current).IsInRole(WindowsBuiltInRole.Administrator); } }
        public XmlDocument ReadIis() {
            var xml = new XmlDocument(); xml.Load(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "System32", "inetsrv", "config", "applicationHost.config")); return xml;
        }
        public SystemdService ReadSystemd(string unit, bool user) {
            var service=SystemdControl.Read(unit,user);SystemdControl.VerifyControl(user,unit).GetAwaiter().GetResult();return service;
        }
        public UpdaterProcessIdentity Launch(string assembly, string configPath) => UpdaterProcess.Start(InteractiveProcessLauncher.GetDotnetPath(), assembly, configPath);
        public void RemoveLegacyTask(string backupPath) => RemoveTask(backupPath);
        public void Protect(string path) {
            if (!OperatingSystem.IsWindows()) { File.SetUnixFileMode(path, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute); return; }
            var acl = new DirectorySecurity(); acl.SetAccessRuleProtection(true, false);
            foreach (var sid in new[] { "S-1-5-18", "S-1-5-32-544" }) acl.AddAccessRule(new FileSystemAccessRule(new SecurityIdentifier(sid), FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit, PropagationFlags.None, AccessControlType.Allow));
            new DirectoryInfo(path).SetAccessControl(acl);
        }
    }
    private void PrepareShared(string directory, string shared) {
        var source = Environment.GetEnvironmentVariable("REMOTETOOL_SHARED_CONFIG") ?? directory;
        foreach (var file in Directory.GetFiles(source, "appsettings*.json")) if (!File.Exists(Path.Combine(shared, Path.GetFileName(file)))) File.Copy(file, Path.Combine(shared, Path.GetFileName(file)));
        var database = Path.GetFullPath(configuration["Hangfire:Database"] ?? "hangfire.db", Environment.CurrentDirectory);
        if (!File.Exists(database)) throw new InvalidOperationException("无法确认现有 Hangfire 数据库位置");
        UpdateContract.Write(Path.Combine(shared, "hosting.json"), new { Hangfire = new { Database = database } });
        var web = new XmlDocument(); web.Load(Path.Combine(directory, "web.config"));
        var aspnet = web.SelectSingleNode("/configuration/location/system.webServer/aspNetCore") ?? web.SelectSingleNode("/configuration/system.webServer/aspNetCore")
            ?? throw new InvalidOperationException("无法识别 IIS 托管配置");
        var variables = aspnet.SelectSingleNode("environmentVariables") ?? aspnet.AppendChild(web.CreateElement("environmentVariables"));
        var variable = (XmlElement)(variables.SelectSingleNode("environmentVariable[@name='REMOTETOOL_SHARED_CONFIG']") ?? variables.AppendChild(web.CreateElement("environmentVariable")));
        variable.SetAttribute("name", "REMOTETOOL_SHARED_CONFIG"); variable.SetAttribute("value", shared);
        web.Save(Path.Combine(shared, "web.config"));
    }
    private static void RemoveTask(string backupPath) {
        if (!OperatingSystem.IsWindows()) return;
        var objects = new List<object>(); dynamic Keep(object value) { objects.Add(value); return value; }
        try {
            dynamic service = Keep(Activator.CreateInstance(Type.GetTypeFromProgID("Schedule.Service", true)!)!); service.Connect();
            dynamic folder = Keep(service.GetFolder("\\"));
            dynamic tasks = Keep(folder.GetTasks(1));
            for (var index = 1; index <= (int)tasks.Count; index++) {
                dynamic task = Keep(tasks[index]);
                if (!string.Equals((string)task.Name, "RemoteTool_ServerUpdate", StringComparison.OrdinalIgnoreCase)) continue;
                if (!File.Exists(backupPath)) File.WriteAllText(backupPath, (string)task.Xml);
                folder.DeleteTask("RemoteTool_ServerUpdate", 0);
                break;
            }
        } finally { for (var i = objects.Count - 1; i >= 0; i--) if (Marshal.IsComObject(objects[i])) Marshal.FinalReleaseComObject(objects[i]); }
    }
}
