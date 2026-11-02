using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using RemoteTool.Updates;
using RemoteTool.WebApi.Services.Codex;
using RemoteTool.WebApi.Services.Updates;

if (args is ["--idle-fixture"]) { await Task.Delay(TimeSpan.FromMinutes(1)); return; }
if (await UpdaterProcessChecks.Handle(args)) return;
await UpdaterProcessChecks.Run();

var checks = 0;
void Assert(bool value, string message) { if (!value) throw new Exception(message); checks++; }
void Reject(Action action) { try { action(); } catch (Exception error) when (error is InvalidDataException or CryptographicException or ArgumentException) { checks++; return; } throw new Exception("Invalid input accepted"); }
var release = new ServerRelease(1, "1.2.3", 12, new string('a', 40), "stable", "1.0.0", 1, 1, true, "Notes",
    new() { [UpdateContract.Platform] = new("https://github.com/bighamx/RemoteTool/releases/download/server-v1.2.3/server.zip", new string('b', 64), 20) });
ServerRelease ReadRelease(ServerRelease value) {
    var data = JsonSerializer.SerializeToUtf8Bytes(value, UpdateContract.Json);
    return UpdateContract.Verify(data);
}
Assert(ReadRelease(release).ReleaseSequence == 12, "Valid release rejected");
Reject(() => ReadRelease(release with { RollbackSupported = false }));
Reject(() => ReadRelease(release with { ProtocolMin = 2 }));
Reject(() => ReadRelease(release with { MinimumUpdaterVersion = "2.0.0" }));
Reject(() => ReadRelease(release with { Packages = new() { [UpdateContract.Platform] = release.Packages[UpdateContract.Platform] with { Url = "https://github.com/attacker/RemoteTool/releases/download/server-v1.2.3/p.zip" } } }));
Reject(() => ReadRelease(release with { Packages = new() { [UpdateContract.Platform] = release.Packages[UpdateContract.Platform] with { Sha256 = "bad" } } }));
var bridgeGate = new CodexMaintenance();
Assert(bridgeGate.TryEnter(), "Bridge request admission failed");
Assert(!bridgeGate.TryBeginShutdown(() => false), "In-flight request must block shutdown");
bridgeGate.Leave(); Assert(!bridgeGate.TryBeginShutdown(() => true), "Running/queued/unknown native work must block shutdown");
Assert(bridgeGate.TryBeginShutdown(() => false) && !bridgeGate.TryEnter(), "Shutdown must atomically fence new requests");
Assert(UpdateContract.InWindow(new("automatic", 22, 3), 1) && !UpdateContract.InWindow(new("automatic", 22, 3), 12), "Overnight update window failed");
var root = Path.Combine(Path.GetTempPath(), "remotetool-update-tests-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(root);
try {
    foreach (var bad in new[] { "../escape", "appsettings.json", "web.config", "sub/appsettings.Production.json", "C:/escape", "hangfire.db", "hangfire.db-wal", "auth.json", "uploads/user.txt" }) {
        var zip = Path.Combine(root, Guid.NewGuid() + ".zip");
        using (var archive = ZipFile.Open(zip, ZipArchiveMode.Create)) { using var writer = new StreamWriter(archive.CreateEntry(bad).Open()); writer.Write("invalid"); }
        Reject(() => UpdateContract.Extract(zip, Path.Combine(root, Guid.NewGuid().ToString("N"))));
    }
    if (OperatingSystem.IsWindows() || OperatingSystem.IsLinux()) {
        foreach (var scenario in new[] { "success", "unhealthy", "power-loss", "busy", "invalid-url" }) {
            var home = Path.Combine(root, scenario); Directory.CreateDirectory(home);
            var shared = Path.Combine(home, "shared"); Directory.CreateDirectory(shared); File.WriteAllText(Path.Combine(shared, "web.config"), "preserved-config");
            var old = Path.Combine(home, "previous"); Directory.CreateDirectory(old);
            var id = Guid.NewGuid().ToString("N");
            var config = new Installation("site", "pool", Path.Combine(home, "releases"), shared, "http://127.0.0.1:1/api/device/update-health", "device", "test-token");
            UpdateContract.Write(Path.Combine(home, "installation.json"), config);
            UpdateContract.Write(Path.Combine(home, "job.json"), new UpdateJob(id, "requested", "", 12, "1.2.3"));
            UpdateContract.Write(Path.Combine(home, "request.json"), new { id, downloadOnly = false });
            var zip = Path.Combine(home, "package-12.zip");
            using (var archive = ZipFile.Open(zip, ZipArchiveMode.Create)) { using var writer = new StreamWriter(archive.CreateEntry("WebApplication.dll").Open()); writer.Write("test-package"); }
            var package = release.Packages[UpdateContract.Platform] with { Size = new FileInfo(zip).Length, Sha256 = Convert.ToHexString(SHA256.HashData(File.ReadAllBytes(zip))) };
            if (scenario == "invalid-url") package = package with { Url = "http://attacker.invalid/package.zip" };
            var manifest = JsonSerializer.SerializeToUtf8Bytes(release with { Packages = new() { [UpdateContract.Platform] = package } }, UpdateContract.Json);
            var folder = Path.Combine(home, "jobs", id); Directory.CreateDirectory(folder);
            File.WriteAllBytes(Path.Combine(folder, "manifest.json"), manifest);
            var runtime = new FakeRuntime(old, scenario);
            if (scenario == "power-loss") {
                var target = Path.Combine(home, "partially-installed"); Directory.CreateDirectory(target); runtime.Path = target;
                UpdateContract.Write(Path.Combine(home, "transaction.json"), new JsonObject { ["previous"] = old, ["sequence"] = 1, ["target"] = target });
            }
            await new Worker(Path.Combine(home, "installation.json"), runtime).Run();
            var result = UpdateContract.Read<UpdateJob>(Path.Combine(home, "job.json"));
            Assert(result.Phase == (scenario switch { "success" => "completed", "busy" => "deferred", "invalid-url" => "failed", _ => "rolled_back" }), "Wrong outcome: " + scenario + ":" + result.Phase + ":" + result.Message);
            if (scenario != "success") Assert(runtime.Path == old, "Failure must preserve/restore old release");
            if (scenario == "unhealthy") {
                Assert(result.Message.Contains("未确认目标版本"), "Rollback hid the original health failure");
                Assert(File.Exists(Path.Combine(folder, "failure.json")), "Original failure was not persisted");
            }
            Assert(!runtime.Commands.Any(args => args.Any(a => a.Contains("hermes", StringComparison.OrdinalIgnoreCase))), "Updater must never restart Hermes");
            if (scenario == "success") Assert(File.ReadAllText(Path.Combine(runtime.Path, "web.config")) == "preserved-config", "Configuration was not preserved");
        }
        foreach (var scenario in new[] { "success", "compile-failure", "bridge-busy", "force-busy", "release-mismatch", "release-dirty" }) {
            var failure = scenario == "compile-failure";
            var folder = Path.Combine(root, "source-" + scenario); Directory.CreateDirectory(folder);
            var project = Path.Combine(folder, "project"); var output = Path.Combine(project, "bin", "Debug", "net8.0"); Directory.CreateDirectory(output);
            File.WriteAllText(Path.Combine(project, "WebApplication.csproj"), "fixture");
            File.Copy(typeof(FakeRuntime).Assembly.Location, Path.Combine(output, "WebApplication.dll"));
            File.WriteAllText(Path.Combine(output, "appsettings.json"), "preserve-config");
            File.WriteAllText(Path.Combine(output, "hangfire.db"), "untouched-user-data");
            File.WriteAllText(Path.Combine(project, "uncommitted-source.txt"), "developer-work");
            var config = new Installation("site", "pool", folder, folder, "http://127.0.0.1:1/api/device/update-health", "device", "test-token", "source", project, output);
            UpdateContract.Write(Path.Combine(folder, "installation.json"), config);
            var releaseSource = scenario.StartsWith("release-");
            var job = new UpdateJob(Guid.NewGuid().ToString("N"), "requested", "", releaseSource ? 12 : 1, "local", SourceBuild: true, ReleaseSource: releaseSource, Force: scenario == "force-busy");
            UpdateContract.Write(Path.Combine(folder, "job.json"), job);
            if (releaseSource) {
                var snapshot = Path.Combine(folder, "jobs", job.Id); Directory.CreateDirectory(snapshot);
                UpdateContract.Write(Path.Combine(snapshot, "manifest.json"), release);
            }
            var runtime = new FakeSourceRuntime(output, failure, scenario is "bridge-busy" or "force-busy", releaseSource ? scenario == "release-mismatch" ? new string('b',40) : release.Commit : null, scenario == "release-dirty");
            await new Worker(Path.Combine(folder, "installation.json"), runtime).Run();
            var result = UpdateContract.Read<UpdateJob>(Path.Combine(folder, "job.json"));
            if (releaseSource) {
                Assert(result.Phase == "failed" && result.Message.Contains("发行提交"), "Legacy source install must reject unmatched/dirty worktrees");
                Assert(runtime.Commands.Count == 0 && !runtime.BridgeStopped && !runtime.Compiled, "Invalid source release stopped services or compiled");
                continue;
            }
            if (scenario == "bridge-busy") {
                Assert(result.Phase == "deferred" && result.Message.Contains("bridge busy"), "Busy bridge must defer before downtime");
                Assert(runtime.Commands.Count == 0 && !runtime.Compiled && !runtime.BridgeWarmed, "Busy bridge must not stop IIS or compile");
                Assert(!File.Exists(Path.Combine(folder, "transaction.json")), "Busy bridge created a destructive transaction");
                continue;
            }
            Assert(result.Phase == (failure ? "rolled_back" : "completed"), "Source rebuild failed: " + result.Message);
            if (failure) Assert(result.Message.Contains("compile failed") && File.Exists(Path.Combine(folder, "jobs", job.Id, "failure.json")), "Source rollback hid the compile error");
            Assert(runtime.BridgeStopped && runtime.BridgeWarmed, "Source rebuild must stop and restore its bridge");
            Assert(runtime.Forced == (scenario == "force-busy"), "Force must be passed only for the explicit job");
            Assert(!runtime.DirectoryChanged, "Developer IIS directory must stay unchanged");
            Assert(File.ReadAllText(Path.Combine(output, "appsettings.json")) == "preserve-config", "Deployment configuration overwritten");
            Assert(File.ReadAllText(Path.Combine(output, "hangfire.db")) == "untouched-user-data", "User database touched");
            Assert(File.ReadAllText(Path.Combine(project, "uncommitted-source.txt")) == "developer-work", "Uncommitted developer work touched");
        }
        // Exercise the production StopBridge HTTP authentication and PID/start-time
        // guard with disposable child processes, never a real user bridge.
        foreach (var scenario in new[] { "forced", "wrong-pid", "wrong-start", "normal-busy" }) {
            var folder = Path.Combine(root, "identity-" + scenario); var updates = Path.Combine(folder, "updates"); var bridge = Path.Combine(folder, "codex-bridge");
            Directory.CreateDirectory(updates); Directory.CreateDirectory(bridge);
            var start = new System.Diagnostics.ProcessStartInfo("dotnet") { UseShellExecute = false, CreateNoWindow = true };
            start.ArgumentList.Add(typeof(FakeRuntime).Assembly.Location); start.ArgumentList.Add("--idle-fixture");
            using var child = System.Diagnostics.Process.Start(start)!;
            using var listener = new System.Net.HttpListener();
            var socket = new System.Net.Sockets.TcpListener(System.Net.IPAddress.Loopback, 0); socket.Start();
            var port = ((System.Net.IPEndPoint)socket.LocalEndpoint).Port; socket.Stop();
            listener.Prefixes.Add($"http://127.0.0.1:{port}/"); listener.Start();
            var server = Task.Run(async () => {
                for (var request = 0; request < (scenario == "normal-busy" ? 2 : 1); request++) {
                    var context = await listener.GetContextAsync();
                    if (context.Request.Headers["Authorization"] != "Bearer fixture-token") throw new Exception("Bridge probe lost authentication");
                    var value = new JsonObject { ["maintenance_shutdown"] = true,
                        ["bridge_pid"] = scenario == "wrong-pid" ? child.Id + 1 : child.Id,
                        ["process_started_ticks"] = child.StartTime.ToUniversalTime().Ticks + (scenario == "wrong-start" ? TimeSpan.TicksPerSecond*2 : 0) };
                    context.Response.StatusCode = request == 0 ? 200 : 409;
                    var data = System.Text.Encoding.UTF8.GetBytes(value.ToJsonString());
                    context.Response.ContentLength64 = data.Length; await context.Response.OutputStream.WriteAsync(data); context.Response.Close();
                }
            });
            try {
                UpdateContract.Write(Path.Combine(bridge, "status.json"), new { pid = child.Id, port });
                UpdateContract.Write(Path.Combine(bridge, "connection.json"), new { token = "fixture-token" });
                var worker = new Worker(Path.Combine(updates, "installation.json"));
                typeof(Worker).GetField("job", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!.SetValue(worker,
                    new UpdateJob(Guid.NewGuid().ToString("N"), "waiting", "", 0, "test", Force: scenario != "normal-busy"));
                var stop = typeof(Worker).GetMethod("StopBridge", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic)!;
                var rejected = false;
                try { await ((Task)stop.Invoke(worker, null)!).WaitAsync(TimeSpan.FromSeconds(10)); }
                catch (IOException) { rejected = true; }
                await server.WaitAsync(TimeSpan.FromSeconds(10));
                Assert(rejected == (scenario != "forced"), "Incorrect force identity decision: " + scenario);
                Assert(child.HasExited == (scenario == "forced"), "Wrong process termination: " + scenario);
            } finally { listener.Stop(); if (!child.HasExited) { child.Kill(true); await child.WaitForExitAsync(); } }
        }
    }
    Console.WriteLine($"Server updates: {checks} contract and real-worker fault checks passed");
} finally { Directory.Delete(root, true); }

sealed class FakeRuntime(string original, string scenario) : IUpdateRuntime
{
    public string Path = original;
    public List<string[]> Commands = [];
    public DateTime UtcNow { get; private set; } = DateTime.UtcNow;
    public Task Delay(int milliseconds) { UtcNow = UtcNow.AddMilliseconds(milliseconds); return Task.CompletedTask; }
    public Task Execute(string[] args) { Commands.Add(args); if (args is ["set", ..]) Path = args.Last()["/physicalPath:".Length..]; return Task.CompletedTask; }
    public Task<string> Directory() => Task.FromResult(Path);
    public Task<JsonObject> Health() => Task.FromResult(new JsonObject { ["id"] = "device", ["sequence"] = Path == original ? 1L : scenario == "unhealthy" ? 999L : 12L });
    public Task<JsonNode> Readiness(string jobId) => Task.FromResult<JsonNode>(new JsonObject { ["ready"] = scenario != "busy", ["blockers"] = new JsonArray("active native task") });
}

sealed class FakeSourceRuntime(string output, bool failure, bool bridgeBusy, string? revision = null, bool dirty = false) : ISourceUpdateRuntime
{
    public bool BridgeStopped, BridgeWarmed, DirectoryChanged;
    public bool Compiled, Forced;
    public List<string[]> Commands = [];
    public DateTime UtcNow { get; private set; } = DateTime.UtcNow;
    public Task Delay(int milliseconds) { UtcNow = UtcNow.AddMilliseconds(milliseconds); return Task.CompletedTask; }
    public Task Execute(string[] arguments) { Commands.Add(arguments); if (arguments[0] == "set") DirectoryChanged = true; return Task.CompletedTask; }
    public Task<string> Directory() => Task.FromResult(output);
    public Task<JsonNode> Readiness(string jobId) => Task.FromResult<JsonNode>(new JsonObject { ["ready"] = true });
    public Task<JsonObject> Health() {
        using var file = File.OpenRead(Path.Combine(output, "WebApplication.dll")); using var pe = new System.Reflection.PortableExecutable.PEReader(file);
        var reader = System.Reflection.Metadata.PEReaderExtensions.GetMetadataReader(pe);
        return Task.FromResult(new JsonObject { ["id"] = "device", ["sequence"] = 1L, ["buildId"] = reader.GetGuid(reader.GetModuleDefinition().Mvid).ToString("N") });
    }
    public Task StopBridge(bool force) { if (bridgeBusy && !force) throw new IOException("bridge busy"); Forced = force; BridgeStopped = true; return Task.CompletedTask; }
    public Task WarmBridge() { BridgeWarmed = true; return Task.CompletedTask; }
    public Task<string> Git(string directory, params string[] args) => revision == null ? throw new Exception("Local rebuild must not run Git") : Task.FromResult(args[0] == "rev-parse" ? revision : dirty ? " M local.cs" : "");
    public Task Compile(string project, string target, long sequence) {
        Compiled = true;
        if (!BridgeStopped) throw new Exception("Compile started before bridge stopped");
        File.WriteAllText(Path.Combine(target, "appsettings.json"), "build-would-overwrite-config");
        if (failure) { File.WriteAllText(Path.Combine(target, "WebApplication.dll"), "partial-build"); throw new IOException("compile failed"); }
        File.Copy(typeof(Worker).Assembly.Location, Path.Combine(target, "WebApplication.dll"), true);
        return Task.CompletedTask;
    }
}
