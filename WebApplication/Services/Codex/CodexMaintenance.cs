namespace RemoteTool.WebApi.Services.Codex;

/** Holds admission closed between an idle check and graceful bridge shutdown. */
internal sealed class CodexMaintenance
{
    private readonly object gate = new();
    private int requests;
    private bool stopping;
    public bool TryEnter() { lock (gate) { if (stopping) return false; requests++; return true; } }
    public void Leave() { lock (gate) requests--; }
    public bool TryBeginShutdown(Func<bool> busy) {
        lock (gate) {
            if (stopping) return true;
            if (requests > 0 || busy()) return false;
            stopping = true; return true;
        }
    }
}
