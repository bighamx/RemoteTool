namespace ChuckieHelper.WebApi.Services;

public sealed class HermesAttachmentCleanup(HermesAttachments attachments, ILogger<HermesAttachmentCleanup> logger) : BackgroundService
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
                var result = await Task.Run(() => attachments.Cleanup(DateTime.UtcNow), stoppingToken);
                if (result.Count > 0) logger.LogInformation("Hermes attachment cleanup removed {Count} files ({Bytes} bytes)", result.Count, result.Bytes);
            }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { return; }
            catch (Exception error) { logger.LogWarning(error, "Hermes attachment cleanup failed; will retry next day"); }
        } while (await timer.WaitForNextTickAsync(stoppingToken));
    }
}
