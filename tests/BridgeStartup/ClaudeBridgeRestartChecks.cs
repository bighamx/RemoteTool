using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using System.Reflection;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Hosting;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.FileProviders;

internal static class ClaudeBridgeRestartChecks
{
    private sealed class Environment(string root) : IWebHostEnvironment {
        public string ApplicationName { get; set; } = "fixture";
        public string EnvironmentName { get; set; } = "Development";
        public string ContentRootPath { get; set; } = root;
        public IFileProvider ContentRootFileProvider { get; set; } = new NullFileProvider();
        public string WebRootPath { get; set; } = root;
        public IFileProvider WebRootFileProvider { get; set; } = new NullFileProvider();
    }
    public static async Task Run(Assembly assembly) {
        var temporary = Path.Combine(Path.GetTempPath(), "remotetool-claude-restart-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(temporary);
        var oldData = System.Environment.GetEnvironmentVariable("REMOTETOOL_DATA_DIR");
        System.Environment.SetEnvironmentVariable("REMOTETOOL_DATA_DIR", temporary);
        int startedPid = 0;
        try {
            var config = new ConfigurationBuilder().Build();
            var type = assembly.GetType("RemoteTool.WebApi.Services.ClaudeBridge", true)!;
            var bridge = Activator.CreateInstance(type, config, new Environment(temporary))!;
            string node;
            try { node = (string)type.GetMethod("FindExecutable", BindingFlags.Instance | BindingFlags.NonPublic)!.Invoke(bridge, ["NodeExecutable", "node"])!; }
            catch (TargetInvocationException error) when (error.InnerException is InvalidOperationException) { Console.WriteLine("SKIP Claude idle activation: Node is unavailable"); return; }
            var scripts = Path.Combine(temporary, "scripts"); Directory.CreateDirectory(scripts);
            foreach (var file in Directory.EnumerateFiles(Path.Combine(Path.GetDirectoryName(assembly.Location)!, "claude"), "*.mjs")) File.Copy(file, Path.Combine(scripts, Path.GetFileName(file)));
            var sdk = Path.Combine(scripts, "node_modules", "@anthropic-ai", "claude-agent-sdk"); Directory.CreateDirectory(sdk);
            File.WriteAllText(Path.Combine(sdk, "package.json"), "{\"type\":\"module\",\"exports\":\"./sdk.mjs\"}");
            File.WriteAllText(Path.Combine(sdk, "sdk.mjs"), "export function query(){throw new Error('Fixture must never run a model')} export async function listSessions(){return []}");
            var data = Path.Combine(temporary, "claude-bridge"); var tools = Path.Combine(temporary, "claude-tools"); Directory.CreateDirectory(data); Directory.CreateDirectory(tools);
            const string token = "isolated-claude-fixture-credential-01234567890123456789";
            File.WriteAllText(Path.Combine(data, "connection.json"), new JsonObject { ["token"] = token }.ToJsonString());
            var manifestPath = Path.Combine(tools, "installation.json");
            File.WriteAllText(manifestPath, new JsonObject { ["NodeExecutable"] = node, ["Executable"] = node, ["script"] = Path.Combine(scripts, "server.mjs"), ["bridge_version"] = 7, ["restart_pending"] = true }.ToJsonString());
            using var socket = new TcpListener(IPAddress.Loopback, 0); socket.Start(); var port = ((IPEndPoint)socket.LocalEndpoint).Port; socket.Stop();
            File.WriteAllText(Path.Combine(data, "status.json"), new JsonObject { ["port"] = port, ["pid"] = System.Environment.ProcessId }.ToJsonString());
            using var listener = new HttpListener(); listener.Prefixes.Add($"http://127.0.0.1:{port}/"); listener.Start();
            bool active = true, waiting = false, stopped = false; int shutdowns = 0;
            var server = Task.Run(async () => {
                try {
                    while (listener.IsListening) {
                        var context = await listener.GetContextAsync(); var path = context.Request.Url!.AbsolutePath;
                        JsonObject result;
                        if (path == "/health" && stopped) { context.Response.StatusCode = 404; result = new(); }
                        else if (path == "/health") result = new() { ["protocol"] = 2, ["version"] = 7, ["active_runs"] = active ? 1 : 0, ["waiting"] = waiting ? 1 : 0, ["pid"] = System.Environment.ProcessId };
                        else if (path == "/shutdown") { shutdowns++; stopped = true; result = new() { ["stopping"] = true }; }
                        else result = new() { ["fixture"] = true };
                        var bytes = Encoding.UTF8.GetBytes(result.ToJsonString()); context.Response.ContentType = "application/json";
                        await context.Response.OutputStream.WriteAsync(bytes); context.Response.Close();
                    }
                } catch (Exception error) when (error is HttpListenerException or ObjectDisposedException) { }
            });
            var send = type.GetMethod("SendAsync")!;
            async Task<HttpResponseMessage> Send(HttpMethod method, string path) => await (Task<HttpResponseMessage>)send.Invoke(bridge, [method, path, method == HttpMethod.Post ? "{}" : null, CancellationToken.None, null, null, false])!;
            try {
                using (var response = await Send(HttpMethod.Get, "capabilities")) {
                    if (!response.IsSuccessStatusCode || shutdowns != 0 || JsonNode.Parse(File.ReadAllText(manifestPath))!["restart_pending"]!.GetValue<bool>() != true)
                        throw new Exception("Configuring components interrupted an active worker");
                }
                active = false; waiting = true;
                using (var response = await Send(HttpMethod.Get, "capabilities")) {
                    if (!response.IsSuccessStatusCode || shutdowns != 0) throw new Exception("Configuration interrupted a pending approval");
                }
                waiting = false;
                using (var response = await Send(HttpMethod.Get, "capabilities")) {
                    var value = JsonNode.Parse(await response.Content.ReadAsStringAsync());
                    if (!response.IsSuccessStatusCode || shutdowns != 1 || value?["features"]?["steer"]?.GetValue<bool>() != true)
                        throw new Exception("Idle configuration did not activate the new worker through the public send path");
                }
                startedPid = JsonNode.Parse(File.ReadAllText(Path.Combine(data, "status.json")))!["pid"]!.GetValue<int>();
                if (JsonNode.Parse(File.ReadAllText(manifestPath))!["restart_pending"]!.GetValue<bool>() != false) throw new Exception("Activation flag was retained after the new worker became healthy");
                using var shutdown = await Send(HttpMethod.Post, "shutdown");
                if (!shutdown.IsSuccessStatusCode) throw new Exception("Fixture worker did not shut down");
                using var process = Process.GetProcessById(startedPid); await process.WaitForExitAsync().WaitAsync(TimeSpan.FromSeconds(8)); startedPid = 0;
            } finally { listener.Stop(); await server; }
            Console.WriteLine("PASS: public Claude send preserves active work, activates configured paths when idle, clears pending activation and shuts down its isolated worker");
        } finally {
            if (startedPid > 0) try { using var owned = Process.GetProcessById(startedPid); if (!owned.HasExited) owned.Kill(true); } catch (ArgumentException) { }
            System.Environment.SetEnvironmentVariable("REMOTETOOL_DATA_DIR", oldData);
            Directory.Delete(temporary, true);
        }
    }
}
