namespace RemoteTool.WebApi.Services;

public sealed class HermesAttachmentCleanup(HermesAttachments attachments, [FromKeyedServices("codex")] HermesAttachments codex, ILogger<HermesAttachmentCleanup> logger) : BackgroundService
{
    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        // Yield before the first file scan so startup is not blocked.
        await Task.Yield();
        using var timer = new PeriodicTimer(TimeSpan.FromDays(1));
        do
        {
            try
            {
                var result = await Task.Run(() => {
                    var first = attachments.Cleanup(DateTime.UtcNow); var second = codex.Cleanup(DateTime.UtcNow);
                    return (Count: first.Count + second.Count, Bytes: first.Bytes + second.Bytes);
                }, stoppingToken);
                if (result.Count > 0) logger.LogInformation("Agent attachment cleanup removed {Count} files ({Bytes} bytes)", result.Count, result.Bytes);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
            catch (Exception error) { logger.LogWarning(error, "Hermes attachment cleanup failed; will retry next day"); }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
