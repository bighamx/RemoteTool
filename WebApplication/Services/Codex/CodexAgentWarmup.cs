namespace RemoteTool.WebApi.Services.Codex;

internal sealed partial class CodexAgent
{
    private readonly object warmupGate = new();
    private readonly CancellationTokenSource warmupShutdown = new();
    private readonly CodexWarmup warmup;
    private Task warmupTask = Task.CompletedTask;

    private void StartWarmupScan()
    {
        if (!maintenance.TryEnter()) return;
        lock (warmupGate)
        {
            if (!warmupTask.IsCompleted || warmupShutdown.IsCancellationRequested) { maintenance.Leave(); return; }
            warmupTask = Task.Run(async () => {
                try { await warmup.Scan(warmupShutdown.Token); }
                catch (Exception) { /* The durable state keeps interrupted submissions available for reconciliation. */ }
                finally { maintenance.Leave(); }
            });
        }
    }
}
