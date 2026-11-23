using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Hosting.Server;
using Microsoft.AspNetCore.Hosting.Server.Features;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexDesktopAppServerChecks
{
    public static async Task Run() {
        if (OperatingSystem.IsWindows()) {
            var home = @"C:\Users\fixture\.codex";
            if (!CodexDesktopAppServer.MatchesHome(home, @"\\?\C:\Users\fixture\.codex\sessions\rollout.jsonl") ||
                CodexDesktopAppServer.MatchesHome(home, @"C:\Users\fixture\.codex-other\rollout.jsonl") ||
                CodexDesktopAppServer.MatchesHome(home, @"C:\Users\fixture\.codex\..\other\rollout.jsonl") ||
                CodexDesktopAppServer.MatchesHome(home, "relative.jsonl")) throw new Exception("Native desktop home must match normalized paths and boundaries");
            if (!CodexDesktopSync.TrustedWindowsProcess(@"C:\Program Files\WindowsApps\OpenAI.Codex_26\app\ChatGPT.exe", 1, 1) ||
                CodexDesktopSync.TrustedWindowsProcess(@"C:\Other\ChatGPT.exe", 1, 1) ||
                CodexDesktopSync.TrustedWindowsProcess(@"C:\Program Files\WindowsApps\OpenAI.Codex_26\app\ChatGPT.exe", 2, 1))
                throw new Exception("Desktop discovery must validate executable and interactive session");
        }
        var builder = WebApplication.CreateBuilder();
        builder.Logging.ClearProviders();
        builder.WebHost.UseUrls("http://127.0.0.1:0");
        await using var app = builder.Build();
        app.UseWebSockets();
        app.Run(async context => {
            using var socket = await context.WebSockets.AcceptWebSocketAsync();
            var buffer = new byte[4096];
            var count = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), context.RequestAborted);
            var request = JsonNode.Parse(buffer.AsSpan(0, count.Count))!;
            if (request["method"]?.ToString() != "Runtime.evaluate" || request["params"]?["expression"]?.ToString() != "fixture '中文' `$()")
                throw new Exception("Desktop expressions must retain literal content");
            await socket.SendAsync(Encoding.UTF8.GetBytes("{\"method\":\"unrelated-event\"}"), WebSocketMessageType.Text, true, context.RequestAborted);
            var reply = Encoding.UTF8.GetBytes(new JsonObject { ["id"] = request["id"]!.DeepClone(), ["result"] = new JsonObject {
                ["result"] = new JsonObject { ["value"] = new JsonObject { ["turn"] = "accepted" } }
            } }.ToJsonString());
            await socket.SendAsync(reply.AsMemory(0, 7), WebSocketMessageType.Text, false, context.RequestAborted);
            await socket.SendAsync(reply.AsMemory(7), WebSocketMessageType.Text, true, context.RequestAborted);
            count = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), context.RequestAborted);
            request = JsonNode.Parse(buffer.AsSpan(0, count.Count))!;
            await socket.SendAsync(Encoding.UTF8.GetBytes(new JsonObject { ["id"] = request["id"]!.DeepClone(),
                ["result"] = new JsonObject { ["exceptionDetails"] = new JsonObject { ["text"] = "fixture failure" } }
            }.ToJsonString()), WebSocketMessageType.Text, true, context.RequestAborted);
        });
        await app.StartAsync();
        try {
            var address = app.Services.GetRequiredService<IServer>().Features.Get<IServerAddressesFeature>()!.Addresses.Single();
            using var connection = new CodexDesktopAppServer.Connection();
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
            await connection.Connect(new Uri(address.Replace("http:", "ws:")), timeout.Token);
            var result = await connection.Evaluate("fixture '中文' `$()", timeout.Token);
            if (result?["turn"]?.ToString() != "accepted") throw new Exception("Fragmented desktop replies must preserve operation results");
            try { await connection.Evaluate("failure", timeout.Token); throw new Exception("Desktop exceptions must fail the operation"); }
            catch (IOException) { }
        } finally { await app.StopAsync(); }
        Console.WriteLine("Desktop AppServer: trusted process, home boundaries, framed replies, literal input and evaluation failure checks passed");
    }
}
