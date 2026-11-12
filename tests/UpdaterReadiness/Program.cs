using System.Net;
using System.Text.Json;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;
using RemoteTool.Updates;
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.Updates;

var root = Path.Combine(Path.GetTempPath(), "remotetool-readiness-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
var checks = 0;
void Assert(bool value, string message) { if (!value) throw new Exception(message); checks++; }
try {
    // Claude: an active or unanswered worker blocks; an idle one clears stale registry hints.
    foreach (var scenario in new[] { "claude-active", "claude-waiting", "claude-unanswered", "claude-idle", "claude-absent" }) {
        RemoteToolPaths.ProgramData=Path.Combine(root,scenario);
        var updates=Path.Combine(RemoteToolPaths.ProgramData,"updates");Directory.CreateDirectory(updates);
        var job=new UpdateJob(Guid.NewGuid().ToString("N"),"waiting","",0,"test",SourceBuild:true);UpdateContract.WriteJob(updates,job);
        var registry=new RunRegistry();registry.Register("claude","session","claude-run");
        var health=scenario switch { "claude-active"=>new ClaudeBridge.Health(1,"t",2,2,1,0,1), "claude-waiting"=>new ClaudeBridge.Health(1,"t",2,2,1,1,1), "claude-idle"=>new ClaudeBridge.Health(1,"t",2,2,0,0,1), _=>null };
        HttpResponseMessage Reply()=>new(HttpStatusCode.OK){Content=new StringContent("{}")};
        using var service=new ServerUpdateService(new Clients(),new ConfigurationBuilder().Build(),new TestEnvironment(root),new HermesManagement(),new HermesProfiles(),new HermesBridge(Reply),new CodexBridge(Reply),new ClaudeBridge(health,scenario=="claude-unanswered"),registry,new UpdaterBootstrap());
        var result=JsonSerializer.SerializeToNode(await service.Readiness(job.Id,default))!;
        var idle=scenario is "claude-idle" or "claude-absent";
        Assert(result["ready"]!.GetValue<bool>()==idle,"Claude readiness: "+scenario+" "+result);
        Assert((registry.Query("claude","session")==null)==idle,"Claude registry hint cleanup: "+scenario);
        if(scenario=="claude-waiting")Assert(result["blockers"]!.AsArray().Any(b=>b!.GetValue<string>().Contains("等待确认")),"Claude waiting blocker text");
        checks++;
    }
    foreach (var scenario in new[] { "old-running", "old-unknown-enqueued", "old-unknown-created", "unknown-no-date", "http-404", "http-500", "completed", "cancelled", "inflight", "force", "reviewed-history", "review-wrong-turn", "reviewed-running" }) {
        RemoteToolPaths.ProgramData = Path.Combine(root, scenario);
        var updates = Path.Combine(RemoteToolPaths.ProgramData, "updates"); Directory.CreateDirectory(updates);
        var job = new UpdateJob(Guid.NewGuid().ToString("N"), "waiting", "", 0, "test", SourceBuild: true, Force: scenario == "force");
        UpdateContract.WriteJob(updates, job);
        var registry = new RunRegistry(); registry.Register("codex", "session", "run");
        var registered = JsonNode.Parse(File.ReadAllText(RemoteToolPaths.RunRegistry))!;
        registered["codex:session"]!["updated_at"] = 1L;
        File.WriteAllText(RemoteToolPaths.RunRegistry, registered.ToJsonString());
        var status = scenario is "old-running" or "reviewed-running" ? "started" : scenario == "completed" || scenario == "inflight" ? "completed" : scenario == "cancelled" ? "cancelled" : "acceptance_unknown";
        var native = new JsonObject { ["status"] = status, ["session_id"] = "session", ["run_id"] = "run", ["turn_id"] = "turn" };
        if (scenario is "reviewed-history" or "review-wrong-turn" or "reviewed-running") native["maintenance_review"] = new JsonObject {
            ["kind"]="historical_tracking", ["reviewed_at"]=1L, ["run_id"]="run", ["session_id"]="session", ["turn_id"]=scenario=="review-wrong-turn"?"other":"turn" };
        if (scenario != "unknown-no-date") native[scenario == "old-unknown-created" ? "created_at" : "enqueued_at"] = 1L;
        Directory.CreateDirectory(RemoteToolPaths.CodexState);
        File.WriteAllText(Path.Combine(RemoteToolPaths.CodexState, "runs.json"), new JsonObject { ["run"] = native }.ToJsonString());
        var nativeBefore = File.ReadAllBytes(Path.Combine(RemoteToolPaths.CodexState, "runs.json"));
        HttpResponseMessage Reply() => new(scenario == "http-404" ? HttpStatusCode.NotFound : scenario == "http-500" ? HttpStatusCode.InternalServerError : HttpStatusCode.OK) {
            Content = new StringContent(native.ToJsonString())
        };
        var bridge = new CodexBridge(Reply);
        using var service = new ServerUpdateService(new Clients(), new ConfigurationBuilder().Build(), new TestEnvironment(root),
            new HermesManagement(), new HermesProfiles(), new HermesBridge(Reply), bridge, new ClaudeBridge(), registry, new UpdaterBootstrap());
        if (scenario == "inflight") Assert(service.Enter(DateTime.UtcNow.Ticks), "Initial admission failed");
        var result = JsonSerializer.SerializeToNode(await service.Readiness(job.Id, default))!;
        var terminal = scenario is "completed" or "cancelled";
        // 只丢了受理确认、且时间戳停留在很久以前的记录不再阻塞（不可能仍在执行）
        var stale = scenario is "old-unknown-enqueued" or "old-unknown-created";
        var ready = terminal || job.Force || scenario == "reviewed-history" || stale;
        Assert(result["ready"]!.GetValue<bool>() == ready, "Wrong readiness: " + scenario + " " + result);
        Assert(bridge.Probes == (job.Force ? 0 : 1), "Duplicate registry/native probes: " + scenario);
        Assert(File.ReadAllBytes(Path.Combine(RemoteToolPaths.CodexState, "runs.json")).SequenceEqual(nativeBefore), "Readiness rewrote native receipt");
        Assert((registry.Query("codex", "session") == null) == (terminal || scenario == "inflight"), "Unsafe registry cleanup: " + scenario);
        Assert(service.Enter(DateTime.UtcNow.Ticks) == !ready, "Admission barrier was not held/released correctly: " + scenario);
        Assert(UpdateContract.RunBlocksMaintenance(native) == (!terminal && scenario != "inflight" && scenario != "reviewed-history"), "Bridge maintenance classification: " + scenario);
        if (scenario.StartsWith("http-")) Assert(result["blockers"]!.ToJsonString().Contains(scenario[5..]), "Probe error missing from blockers");
        if (scenario == "force") {
            UpdateContract.WriteJob(updates, job with { Phase = "completed", Force = false });
            UpdateContract.Write(Path.Combine(updates, "installation.json"), new Installation("site", "pool", root, root, "http://127.0.0.1:1", "device", "fixture", Mode: "source",Host:OperatingSystem.IsLinux()?"systemd":"iis"));
            var rejected = false;
            try { await service.Install(0, default, rebuild:true, force:true); }
            catch (InvalidOperationException error) { rejected = error.Message.Contains("更新器"); }
            Assert(rejected, "Old updater must reject force before scheduling a job");
            Assert(!UpdateContract.Read<UpdateJob>(Path.Combine(updates, "job.json")).Force, "Rejected force request changed the accepted job");
        }
    }
    foreach (var scenario in new[] { "idle", "stale", "active", "generic-404", "server-error" }) {
        RemoteToolPaths.ProgramData=Path.Combine(root,"hermes-"+scenario);
        var updates=Path.Combine(RemoteToolPaths.ProgramData,"updates");Directory.CreateDirectory(updates);
        var job=new UpdateJob(Guid.NewGuid().ToString("N"),"waiting","",0,"test",SourceBuild:true);UpdateContract.WriteJob(updates,job);
        var registry=new RunRegistry();registry.Register("hermes","session","expired-run");
        var profiles=new HermesProfiles();Directory.CreateDirectory(profiles.Root);
        var management=new HermesManagement(()=>JsonSerializer.SerializeToElement(new {running=true,fresh=scenario!="stale",active=scenario=="active"?1:0}));
        HttpResponseMessage Reply()=>new(scenario=="server-error"?HttpStatusCode.InternalServerError:HttpStatusCode.NotFound){Content=new StringContent(scenario=="generic-404"?"{}":"{\"error\":{\"code\":\"run_not_found\"}}")};
        using var service=new ServerUpdateService(new Clients(),new ConfigurationBuilder().Build(),new TestEnvironment(root),management,profiles,new HermesBridge(Reply),new CodexBridge(Reply),new ClaudeBridge(),registry,new UpdaterBootstrap());
        var result=JsonSerializer.SerializeToNode(await service.Readiness(job.Id,default))!;
        Assert(result["ready"]!.GetValue<bool>()==(scenario=="idle"),"Hermes readiness: "+scenario);
        Assert((registry.Query("hermes","session")==null)==(scenario=="idle"),"Hermes hint cleanup needs fresh idle plus native not-found: "+scenario);
    }
    Console.WriteLine($"Updater readiness: {checks} real-service checks passed");
} finally { Directory.Delete(root, true); }

sealed class Clients : IHttpClientFactory { public HttpClient CreateClient(string name) => new(); }
sealed class TestEnvironment(string root) : IWebHostEnvironment {
    public string ApplicationName { get; set; } = "tests";
    public string EnvironmentName { get; set; } = "Testing";
    public string ContentRootPath { get; set; } = root;
    public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
    public string WebRootPath { get; set; } = root;
    public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
}
