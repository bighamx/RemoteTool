using System.Formats.Tar;
using System.IO.Compression;
using System.Net;
using System.Reflection;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;

internal static class ClaudeConfigurationChecks
{
    private sealed class Environment(string root) : IWebHostEnvironment {
        public string ApplicationName { get; set; } = "fixture";
        public string EnvironmentName { get; set; } = "Development";
        public string ContentRootPath { get; set; } = root;
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
        public string WebRootPath { get; set; } = root;
        public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
    }
    private sealed class NodeDownload(bool corrupt = false) : HttpMessageHandler {
        public int Requests;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken ct) {
            Requests++;
            if (request.RequestUri!.Host != "nodejs.org") throw new Exception("Unexpected download host");
            var os = OperatingSystem.IsWindows() ? "win" : OperatingSystem.IsMacOS() ? "darwin" : "linux";
            var arch = RuntimeInformation.ProcessArchitecture == Architecture.Arm64 ? "arm64" : "x64";
            var name = "node-v24.99.0-" + os + "-" + arch;
            var files = new[] { OperatingSystem.IsWindows() ? "node.exe" : "bin/node", OperatingSystem.IsWindows() ? "node_modules/npm/bin/npm-cli.js" : "lib/node_modules/npm/bin/npm-cli.js" };
            using var memory = new MemoryStream();
            if (OperatingSystem.IsWindows()) {
                using (var zip = new ZipArchive(memory, ZipArchiveMode.Create, true))
                    foreach (var file in files) using (var stream = zip.CreateEntry(name + "/" + file).Open()) stream.WriteByte(1);
            } else {
                using var gzip = new GZipStream(memory, CompressionMode.Compress, true);
                using (var tar = new TarWriter(gzip, true)) foreach (var file in files) {
                    var entry = new PaxTarEntry(TarEntryType.RegularFile, name + "/" + file) { DataStream = new MemoryStream([1]) };
                    tar.WriteEntry(entry);
                }
            }
            var bytes = memory.ToArray();
            var extension = OperatingSystem.IsWindows() ? ".zip" : ".tar.gz";
            HttpContent content = request.RequestUri.AbsolutePath.EndsWith("SHASUMS256.txt")
                ? new StringContent((corrupt ? new string('0', 64) : Convert.ToHexString(SHA256.HashData(bytes))) + "  " + name + extension + "\n")
                : new ByteArrayContent(bytes);
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.OK) { Content = content });
        }
    }
    public static async Task Run(Assembly assembly) {
        var temporary = Path.Combine(Path.GetTempPath(), "remotetool-claude-config-" + Guid.NewGuid().ToString("N"));
        var oldData = System.Environment.GetEnvironmentVariable("REMOTETOOL_DATA_DIR");
        try {
            foreach (var mode in new[] { "existing", "missing", "outdated", "broken-sdk", "sdk-failure", "checksum", "cancelled" }) {
                var folder = Path.Combine(temporary, mode); Directory.CreateDirectory(folder);
                System.Environment.SetEnvironmentVariable("REMOTETOOL_DATA_DIR", folder);
                var project = Path.Combine(folder, "project");
                var source = Path.Combine(project, "Tools", "ClaudeBridge"); Directory.CreateDirectory(source);
                File.WriteAllText(Path.Combine(project, "WebApplication.csproj"), "<Project />");
                File.WriteAllText(Path.Combine(source, "server.mjs"), "// fixture");
                File.WriteAllText(Path.Combine(source, "package.json"), "{}"); File.WriteAllText(Path.Combine(source, "package-lock.json"), "{}");
                void Sdk(string directory) {
                    var sdk = Path.Combine(directory, "node_modules", "@anthropic-ai", "claude-agent-sdk"); Directory.CreateDirectory(sdk);
                    File.WriteAllText(Path.Combine(sdk, "package.json"), "{}"); File.WriteAllText(Path.Combine(sdk, "sdk.mjs"), "// fixture");
                }
                var node = Path.Combine(folder, "node", OperatingSystem.IsWindows() ? "node.exe" : "bin/node");
                Directory.CreateDirectory(Path.GetDirectoryName(node)!); File.WriteAllText(node, "fixture");
                var npm = OperatingSystem.IsWindows() ? Path.Combine(Path.GetDirectoryName(node)!, "node_modules/npm/bin/npm-cli.js") : Path.Combine(folder, "node/lib/node_modules/npm/bin/npm-cli.js");
                Directory.CreateDirectory(Path.GetDirectoryName(npm)!); File.WriteAllText(npm, "fixture");
                var cli = Path.Combine(folder, OperatingSystem.IsWindows() ? "claude.exe" : "claude"); File.WriteAllText(cli, "fixture");
                var existing = mode is "existing" or "outdated" or "broken-sdk" or "cancelled";
                if (existing) Sdk(source);
                var config = new ConfigurationBuilder().Build();
                var bridgeType = assembly.GetType("RemoteTool.WebApi.Services.ClaudeBridge", true)!;
                var bridge = Activator.CreateInstance(bridgeType, config, new Environment(project))!;
                var type = assembly.GetType("RemoteTool.WebApi.Services.ClaudeInstaller", true)!;
                var calls = new List<string[]>();
                using var cancel = new CancellationTokenSource();
                var validation = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
                Func<string, string[], CancellationToken, Task<string>> runner = async (file, args, ct) => {
                    calls.Add(args);
                    if (mode == "cancelled") { await Task.Delay(Timeout.Infinite, ct); return ""; }
                    if (args.SequenceEqual(new[] { "--version" })) return file.EndsWith("node.exe") || file.EndsWith("/node") || file.EndsWith("\\node") ? "v24.99.0" : "2.1.322 (Claude Code)";
                    if (args.LastOrDefault() == "--help") return mode == "outdated" && file == cli ? "legacy command" : "--json";
                    if (args.Contains("--input-type=module")) { if (mode == "sdk-failure" || mode == "broken-sdk" && args.Last().Contains("project")) throw new IOException("SDK validation fixture failure"); return ""; }
                    var prefix = args[Array.IndexOf(args, "--prefix") + 1];
                    if (args.Contains("ci")) {
                        if (!args.Contains("--omit=optional") || !args.Contains("--omit=dev")) throw new Exception("SDK installation options changed");
                        Sdk(prefix);
                    } else {
                        if (!args.Contains("@anthropic-ai/claude-code@latest") || !args.Contains("https://registry.npmjs.org")) throw new Exception("Unexpected CLI source");
                        var binary = Path.Combine(prefix, OperatingSystem.IsWindows() ? "node_modules" : "lib/node_modules", "@anthropic-ai", "claude-code", "bin", OperatingSystem.IsWindows() ? "claude.exe" : "claude");
                        Directory.CreateDirectory(Path.GetDirectoryName(binary)!); File.WriteAllText(binary, "fixture");
                    }
                    return "";
                };
                using var downloader = new NodeDownload(mode == "checksum"); using var http = new HttpClient(downloader);
                Func<string, string, string?> locate = (_, name) => existing ? name == "node" ? node : cli : null;
                Func<CancellationToken, Task<bool>> verify = ct => mode == "existing" ? validation.Task.WaitAsync(ct) : Task.FromResult(false);
                var constructor = type.GetConstructors(BindingFlags.NonPublic | BindingFlags.Instance).Single();
                var installer = constructor.Invoke([Path.Combine(folder, "claude-tools"), config, bridge, http, runner, verify, cancel.Token, locate]);
                var first = type.GetMethod("Start")!.Invoke(installer, null)!;
                var second = type.GetMethod("Start")!.Invoke(installer, null)!;
                var statusType = first.GetType();
                if (statusType.GetProperty("Id")!.GetValue(first)?.ToString() != statusType.GetProperty("Id")!.GetValue(second)?.ToString()) throw new Exception("Duplicate configuration created a second job");
                validation.TrySetResult(true); if (mode == "cancelled") cancel.Cancel();
                await ((Task)type.GetProperty("Completion", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(installer)!).WaitAsync(TimeSpan.FromSeconds(8));
                var status = type.GetProperty("Current")!.GetValue(installer)!;
                var phase = statusType.GetProperty("Phase")!.GetValue(status)!.ToString();
                var success = mode is "existing" or "missing" or "outdated" or "broken-sdk";
                if (phase != (success ? "ready" : "failed")) throw new Exception(mode + ": unexpected phase " + phase);
                var manifest = Path.Combine(folder, "claude-tools", "installation.json");
                if (File.Exists(manifest) != success) throw new Exception("A failed setup published its runtime configuration");
                if (success) {
                    var installed = JsonNode.Parse(File.ReadAllText(manifest))!;
                    foreach (var key in new[] { "NodeExecutable", "Executable", "script" }) if (!File.Exists(installed[key]!.ToString())) throw new Exception("Published path does not exist");
                }
                if (mode == "existing" && (downloader.Requests != 0 || calls.Any(args => args.Contains("install") || args.Contains("ci")))) throw new Exception("Existing installations were replaced");
                if (mode == "sdk-failure") {
                    var downloads = downloader.Requests;
                    type.GetMethod("Start")!.Invoke(installer, null);
                    await ((Task)type.GetProperty("Completion", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(installer)!).WaitAsync(TimeSpan.FromSeconds(8));
                    if (downloader.Requests != downloads || Directory.EnumerateDirectories(Path.Combine(folder, "claude-tools"), "node-v24.*").Count() != 1)
                        throw new Exception("Retrying SDK setup downloaded or duplicated an already validated Node runtime");
                }
                if (Directory.EnumerateDirectories(Path.Combine(folder, "claude-tools"), "staging-*").Any()) throw new Exception("Temporary installation files were retained");
            }
            Console.WriteLine("PASS: Claude one-click configuration, existing runtime reuse, Node checksum and extraction, CLI/SDK installation, idempotent job, failed validation, cancellation and staging cleanup");
        } finally {
            System.Environment.SetEnvironmentVariable("REMOTETOOL_DATA_DIR", oldData);
            if (Directory.Exists(temporary)) Directory.Delete(temporary, true);
        }
    }
}
