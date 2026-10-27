namespace RemoteTool.WebApi.Services.PhoneControl;

/// <summary>Idle expiry also removes the phone-side presence without waiting for another client request.</summary>
public sealed class PhoneSessionReaper(PhoneGateway gateway, ILogger<PhoneSessionReaper> logger) : BackgroundService {
    protected override async Task ExecuteAsync(CancellationToken stoppingToken) {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(1));
        try {
            while (await timer.WaitForNextTickAsync(stoppingToken)) {
                try { await gateway.ExpireSessions(stoppingToken); }
                catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
                catch (Exception error) { logger.LogWarning(error, "Phone session idle cleanup failed"); }
            }
        } catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { }
    }
}
