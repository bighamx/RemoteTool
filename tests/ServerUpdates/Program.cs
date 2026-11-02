using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using RemoteTool.Updates;
using RemoteTool.WebApi.Services.Codex;

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
    foreach (var bad in new[] { "../escape", "appsettings.json", "web.config", "sub/appsettings.Production.json", "C:/escape" }) {
        var zip = Path.Combine(root, Guid.NewGuid() + ".zip");
        using (var archive = ZipFile.Open(zip, ZipArchiveMode.Create)) { using var writer = new StreamWriter(archive.CreateEntry(bad).Open()); writer.Write("invalid"); }
        Reject(() => UpdateContract.Extract(zip, Path.Combine(root, Guid.NewGuid().ToString("N"))));
    }
    if (OperatingSystem.IsWindows()) {
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
            Assert(!runtime.Commands.Any(args => args.Any(a => a.Contains("hermes", StringComparison.OrdinalIgnoreCase))), "Updater must never restart Hermes");
            if (scenario == "success") Assert(File.ReadAllText(Path.Combine(runtime.Path, "web.config")) == "preserved-config", "Configuration was not preserved");
        }
        foreach (var failure in new[] { false, true }) {
            var folder = Path.Combine(root, "source-" + failure); Directory.CreateDirectory(folder);
            var project = Path.Combine(folder, "project"); var output = Path.Combine(project, "bin", "Debug", "net8.0"); Directory.CreateDirectory(output);
            File.WriteAllText(Path.Combine(project, "WebApplication.csproj"), "fixture");
            File.Copy(typeof(FakeRuntime).Assembly.Location, Path.Combine(output, "WebApplication.dll"));
            File.WriteAllText(Path.Combine(output, "appsettings.json"), "preserve-config");
            File.WriteAllText(Path.Combine(output, "hangfire.db"), "untouched-user-data");
            File.WriteAllText(Path.Combine(project, "uncommitted-source.txt"), "developer-work");
            var config = new Installation("site", "pool", folder, folder, "http://127.0.0.1:1/api/device/update-health", "device", "test-token", "source", project, output);
            UpdateContract.Write(Path.Combine(folder, "installation.json"), config);
            var job = new UpdateJob(Guid.NewGuid().ToString("N"), "requested", "", 1, "local", SourceBuild: true);
            UpdateContract.Write(Path.Combine(folder, "job.json"), job);
            var runtime = new FakeSourceRuntime(output, failure);
            await new Worker(Path.Combine(folder, "installation.json"), runtime).Run();
            var result = UpdateContract.Read<UpdateJob>(Path.Combine(folder, "job.json"));
            Assert(result.Phase == (failure ? "rolled_back" : "completed"), "Source rebuild failed: " + result.Message);
            Assert(runtime.BridgeStopped && runtime.BridgeWarmed, "Source rebuild must stop and restore its bridge");
            Assert(!runtime.DirectoryChanged, "Developer IIS directory must stay unchanged");
            Assert(File.ReadAllText(Path.Combine(output, "appsettings.json")) == "preserve-config", "Deployment configuration overwritten");
            Assert(File.ReadAllText(Path.Combine(output, "hangfire.db")) == "untouched-user-data", "User database touched");
            Assert(File.ReadAllText(Path.Combine(project, "uncommitted-source.txt")) == "developer-work", "Uncommitted developer work touched");
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

sealed class FakeSourceRuntime(string output, bool failure) : ISourceUpdateRuntime
{
    public bool BridgeStopped, BridgeWarmed, DirectoryChanged;
    public DateTime UtcNow { get; private set; } = DateTime.UtcNow;
    public Task Delay(int milliseconds) { UtcNow = UtcNow.AddMilliseconds(milliseconds); return Task.CompletedTask; }
    public Task Execute(string[] arguments) { if (arguments[0] == "set") DirectoryChanged = true; return Task.CompletedTask; }
    public Task<string> Directory() => Task.FromResult(output);
    public Task<JsonNode> Readiness(string jobId) => Task.FromResult<JsonNode>(new JsonObject { ["ready"] = true });
    public Task<JsonObject> Health() {
        using var file = File.OpenRead(Path.Combine(output, "WebApplication.dll")); using var pe = new System.Reflection.PortableExecutable.PEReader(file);
        var reader = System.Reflection.Metadata.PEReaderExtensions.GetMetadataReader(pe);
        return Task.FromResult(new JsonObject { ["id"] = "device", ["sequence"] = 1L, ["buildId"] = reader.GetGuid(reader.GetModuleDefinition().Mvid).ToString("N") });
    }
    public Task StopBridge() { BridgeStopped = true; return Task.CompletedTask; }
    public Task WarmBridge() { BridgeWarmed = true; return Task.CompletedTask; }
    public Task<string> Git(string directory, params string[] args) => throw new Exception("Local rebuild must not run Git");
    public Task Compile(string project, string target, long sequence) {
        if (!BridgeStopped) throw new Exception("Compile started before bridge stopped");
        File.WriteAllText(Path.Combine(target, "appsettings.json"), "build-would-overwrite-config");
        if (failure) { File.WriteAllText(Path.Combine(target, "WebApplication.dll"), "partial-build"); throw new IOException("compile failed"); }
        File.Copy(typeof(Worker).Assembly.Location, Path.Combine(target, "WebApplication.dll"), true);
        return Task.CompletedTask;
    }
}
