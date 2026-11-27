using Hangfire;
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.Codex;

namespace RemoteTool.WebApi.Jobs;

public sealed class CodexWarmupJob(CodexBridge bridge)
{
    [AutomaticRetry(Attempts = 0)]
    public async Task Execute(CancellationToken ct)
    {
        if (!CodexWarmup.NeedsScan(RemoteToolPaths.CodexState)) return;
        using var response = await bridge.SendAsync(HttpMethod.Post, "workspaces/warmup/scan",
            System.Text.Json.JsonSerializer.SerializeToElement(new { }), ct);
        response.EnsureSuccessStatusCode();
    }
}
