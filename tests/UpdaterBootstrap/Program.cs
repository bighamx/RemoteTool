using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;
using RemoteTool.Updates;
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.Updates;
using System.Xml;

var assertions = 0;
void Check(bool value, string message) { if (!value) throw new Exception(message); assertions++; }
foreach (var scenario in new[] { "source", "release", "denied", "shared-pool", "retry", "linux-source", "linux-release", "linux-kill-group", "linux-foreign" }) {
    var temporary = Path.Combine(Path.GetTempPath(), "remotetool-bootstrap-" + Guid.NewGuid().ToString("N"));
    Directory.CreateDirectory(temporary);
    try {
        RemoteToolPaths.Data = Path.Combine(temporary, "data");
        var project = Path.Combine(temporary, "project"); var runtime = Path.Combine(project, "bin", "Debug", "net8.0");
        Directory.CreateDirectory(runtime);
        var releaseMode = scenario == "release" || scenario.StartsWith("linux-") && scenario != "linux-source";
        if (!releaseMode) File.WriteAllText(Path.Combine(project, "WebApplication.csproj"), "source-fixture");
        const string originalConfig = "{\"Auth\":{\"Username\":\"fixture\"}}";
        File.WriteAllText(Path.Combine(runtime, "appsettings.json"), originalConfig);
        const string originalWeb = "<configuration><location><system.webServer><aspNetCore processPath='dotnet' arguments='./WebApplication.dll'/></system.webServer></location></configuration>";
        File.WriteAllText(Path.Combine(runtime, "web.config"), originalWeb);
        var database = Path.Combine(temporary, "existing.db"); File.WriteAllText(database, "unchanged-data-fixture");
        foreach (var name in new[] { "WebApplication.dll", "WebApplication.deps.json", "WebApplication.runtimeconfig.json", "dependency.dll" }) File.WriteAllText(Path.Combine(runtime, name), name);
        var platform = Path.Combine(runtime, "runtimes", "win", "lib", "net8.0"); Directory.CreateDirectory(platform);
        File.WriteAllText(Path.Combine(platform, "platform.dll"), "fixture");
        var host = new Host(runtime, scenario);
        var bootstrap = new UpdaterBootstrap(new EnvironmentFixture(runtime), new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string> { ["Hangfire:Database"] = database, ["urls"]="http://0.0.0.0:8890" }).Build(), new DeviceIdentity(), host);
        await bootstrap.Ensure(CancellationToken.None);
        var settings = Path.Combine(RemoteToolPaths.Data, "updates", "installation.json");
        if (scenario is "denied" or "shared-pool" or "linux-kill-group" or "linux-foreign") {
            Check(!File.Exists(settings) && host.Cleanups == 0, "Invalid host must not prepare update configuration");
            Check(!string.IsNullOrEmpty(bootstrap.Message), "Failure must be reported");
            continue;
        }
        var configuration = UpdateContract.Read<Installation>(settings);
        Check(configuration.Mode == (releaseMode ? "release" : "source") && configuration.RuntimeDirectory == runtime && configuration.ProjectDirectory == (releaseMode ? null : project), "Installation layout not retained");
        Check(configuration.DeviceId == "fixture-device" && configuration.WorkerToken.Length == 64, "Internal configuration invalid");
        Check(configuration.Host == host.Kind, "Hosting platform was not retained");
        var engine = configuration.UpdaterAssembly!;
        Check(engine.StartsWith(Path.Combine(RemoteToolPaths.Data, "updates", "engine")) && File.Exists(engine), "Engine must run outside website bin");
        Check(engine.EndsWith("WebApplication.dll") && File.Exists(Path.Combine(Path.GetDirectoryName(engine)!, "dependency.dll")) && !File.Exists(Path.Combine(Path.GetDirectoryName(engine)!, "appsettings.json")), "DLL engine must retain dependencies but exclude configuration");
        Check(File.Exists(Path.Combine(Path.GetDirectoryName(engine)!, "runtimes", "win", "lib", "net8.0", "platform.dll")), "Platform dependency paths were flattened or lost");
        Check(File.ReadAllText(Path.Combine(runtime, "appsettings.json")) == originalConfig && File.ReadAllText(Path.Combine(runtime, "web.config")) == originalWeb, "Runtime configuration modified");
        if (scenario == "release") {
            var hosting = UpdateContract.Read<System.Text.Json.Nodes.JsonObject>(Path.Combine(configuration.Shared,"hosting.json"));
            Check(hosting["Hangfire"]?["Database"]?.ToString() == database && File.ReadAllText(database) == "unchanged-data-fixture", "Existing database path/data changed");
        }
        Check(!File.Exists(Path.Combine(RemoteToolPaths.Data, "updates", "job.json")), "Bootstrap must never start an update");
        var count = host.Cleanups; var token = configuration.WorkerToken;
        await bootstrap.Ensure(CancellationToken.None);
        Check(host.Cleanups == count && UpdateContract.Read<Installation>(settings).WorkerToken == token, "Existing installation must be preserved");
        UpdateContract.Write(settings, configuration with { UpdaterVersion = null });
        // Existing busy jobs must keep the registered updater unchanged.
        var busyJob = new UpdateJob(Guid.NewGuid().ToString("N"), "waiting", "", 0, "fixture");
        UpdateContract.WriteJob(Path.GetDirectoryName(settings)!, busyJob);
        await bootstrap.Ensure(CancellationToken.None);
        Check(host.Cleanups == count && UpdateContract.Read<Installation>(settings).UpdaterVersion == null, "Busy updater must not be replaced");
        UpdateContract.WriteJob(Path.GetDirectoryName(settings)!, busyJob with { Phase = "completed" });
        await bootstrap.Ensure(CancellationToken.None);
        if (scenario == "retry") {
            Check(UpdateContract.Read<Installation>(settings).UpdaterVersion == null, "Failed legacy cleanup committed new installation");
            await bootstrap.Ensure(CancellationToken.None);
        }
        var upgraded = UpdateContract.Read<Installation>(settings);
        Check(host.Cleanups == count + (scenario == "retry" ? 2 : 1) && upgraded.UpdaterVersion == UpdateContract.UpdaterVersion && upgraded.WorkerToken == token && upgraded.RuntimeDirectory == configuration.RuntimeDirectory, "Engine migration changed configuration");
        UpdateContract.WriteJob(Path.GetDirectoryName(settings)!,busyJob);
        await bootstrap.Start(busyJob.Id,default);
        Check(host.Launches == 1 && host.Executable == engine, "Web did not directly launch the outside DLL");
    } finally { Directory.Delete(temporary, true); }
}
Console.WriteLine($"Updater bootstrap: {assertions} first-install/idempotence/permission/retry checks passed");

sealed class Host(string runtime, string scenario) : IUpdaterBootstrapHost {
    public string Kind => scenario.StartsWith("linux-") ? "systemd" : "iis";
    public bool IsElevated => scenario != "denied";
    public int Cleanups, Launches;
    public string Executable;
    public SystemdService ReadSystemd(string unit,bool user) => new(unit,runtime,scenario == "linux-kill-group" ? "control-group" : "process",Environment.ProcessId+(scenario == "linux-foreign" ? 1:0),user);
    public void Protect(string path) { }
    public XmlDocument ReadIis() {
        var xml = new XmlDocument();
        xml.LoadXml("<configuration><system.applicationHost><sites><site name='RemoteTool'><application path='/' applicationPool='pool'><virtualDirectory path='/' physicalPath='" + System.Security.SecurityElement.Escape(runtime) + "'/></application><bindings><binding protocol='http' bindingInformation='*:8888:'/></bindings></site>" +
            (scenario == "shared-pool" ? "<site name='Other'><application path='/' applicationPool='pool'/></site>" : "") + "</sites></system.applicationHost></configuration>"); return xml;
    }
    public void RemoveLegacyTask(string path) {
        Cleanups++; if (scenario == "retry" && Cleanups == 1) throw new IOException("fixture cleanup error");
    }
    public UpdaterProcessIdentity Launch(string assembly,string configPath) {
        Launches++; Executable=assembly;
        var job=UpdateContract.Read<UpdateJob>(Path.Combine(Path.GetDirectoryName(configPath)!,"job.json"));
        using var process=System.Diagnostics.Process.GetCurrentProcess();
        UpdateContract.Write(Path.Combine(Path.GetDirectoryName(configPath)!,"jobs",job.Id,"worker.json"),new {pid=process.Id,startCookie=process.StartTime.ToUniversalTime().Ticks});
        return new(process.Id,process.StartTime.ToUniversalTime().Ticks);
    }
}
sealed class EnvironmentFixture(string root) : IWebHostEnvironment {
    public string ApplicationName { get; set; } = "test";
    public string EnvironmentName { get; set; } = "Development";
    public string ContentRootPath { get; set; } = root;
    public string WebRootPath { get; set; } = root;
    public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
    public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
}
namespace RemoteTool.WebApi.Services {
    public static class RemoteToolPaths { public static string Data; public static string ProgramData => Data; }
    public sealed class DeviceIdentity { public string Id => "fixture-device"; }
}
namespace RemoteTool.WebApi.Services.RemoteControl {
    public static class InteractiveProcessLauncher {
        public static string GetInteractiveUserSid() => throw new Exception("Real user/task lookup must not run in fixture");
        public static string GetDotnetPath() => throw new Exception("Real dotnet lookup must not run in fixture");
    }
}
