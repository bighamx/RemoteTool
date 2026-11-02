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
    foreach (var scenario in new[] { "old-running", "old-unknown-enqueued", "old-unknown-created", "unknown-no-date", "http-404", "http-500", "completed", "cancelled", "inflight", "force" }) {
        RemoteToolPaths.ProgramData = Path.Combine(root, scenario);
        var updates = Path.Combine(RemoteToolPaths.ProgramData, "updates"); Directory.CreateDirectory(updates);
        var job = new UpdateJob(Guid.NewGuid().ToString("N"), "waiting", "", 0, "test", SourceBuild: true, Force: scenario == "force");
        UpdateContract.WriteJob(updates, job);
        var registry = new RunRegistry(); registry.Register("codex", "session", "run");
        var registered = JsonNode.Parse(File.ReadAllText(RemoteToolPaths.RunRegistry))!;
        registered["codex:session"]!["updated_at"] = 1L;
        File.WriteAllText(RemoteToolPaths.RunRegistry, registered.ToJsonString());
        var status = scenario == "old-running" ? "started" : scenario == "completed" || scenario == "inflight" ? "completed" : scenario == "cancelled" ? "cancelled" : "acceptance_unknown";
        var native = new JsonObject { ["status"] = status, ["session_id"] = "session" };
        if (scenario != "unknown-no-date") native[scenario == "old-unknown-created" ? "created_at" : "enqueued_at"] = 1L;
        Directory.CreateDirectory(RemoteToolPaths.CodexState);
        File.WriteAllText(Path.Combine(RemoteToolPaths.CodexState, "runs.json"), new JsonObject { ["run"] = native }.ToJsonString());
        var nativeBefore = File.ReadAllBytes(Path.Combine(RemoteToolPaths.CodexState, "runs.json"));
        HttpResponseMessage Reply() => new(scenario == "http-404" ? HttpStatusCode.NotFound : scenario == "http-500" ? HttpStatusCode.InternalServerError : HttpStatusCode.OK) {
            Content = new StringContent(new JsonObject { ["status"] = status }.ToJsonString())
        };
        var bridge = new CodexBridge(Reply);
        using var service = new ServerUpdateService(new Clients(), new ConfigurationBuilder().Build(), new TestEnvironment(root),
            new HermesManagement(), new HermesProfiles(), new HermesBridge(Reply), bridge, registry, new UpdaterBootstrap());
        if (scenario == "inflight") Assert(service.Enter(), "Initial admission failed");
        var result = JsonSerializer.SerializeToNode(await service.Readiness(job.Id, default))!;
        var terminal = scenario is "completed" or "cancelled";
        Assert(result["ready"]!.GetValue<bool>() == (terminal || job.Force), "Wrong readiness: " + scenario + " " + result);
        Assert(bridge.Probes == (job.Force ? 0 : 1), "Duplicate registry/native probes: " + scenario);
        Assert(File.ReadAllBytes(Path.Combine(RemoteToolPaths.CodexState, "runs.json")).SequenceEqual(nativeBefore), "Readiness rewrote native receipt");
        Assert((registry.Query("codex", "session") == null) == (terminal || scenario == "inflight"), "Unsafe registry cleanup: " + scenario);
        Assert(service.Enter() == !(terminal || job.Force), "Admission barrier was not held/released correctly: " + scenario);
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
