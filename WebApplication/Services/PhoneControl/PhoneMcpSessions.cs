namespace RemoteTool.WebApi.Services.PhoneControl;

public sealed class PhoneMcpSessions {
    public sealed record Client(string Id, string Binding, string Name) {
        public string LogicalId { get; init; } = Id;
        public string ResumeToken { get; init; } = Convert.ToHexString(System.Security.Cryptography.RandomNumberGenerator.GetBytes(32));
        public int Generation { get; init; } = 1;
        public string AgentId => Binding + ":" + LogicalId;
        internal long Touched = Environment.TickCount64;
        internal int Active;
    }
    private readonly object gate = new();
    private readonly Dictionary<string, Client> clients = new();
    private readonly Dictionary<string, Client> recoverable = new();
    public Client Create(string binding, string name, string? resumeToken = null) {
        lock (gate) {
            Expire();
            Client? previous = null;
            if (resumeToken != null) {
                if (!recoverable.TryGetValue(resumeToken, out previous) || previous.Binding != binding)
                    throw new PhoneMcpSessionException("session_recovery_failed", "Recovery credentials are invalid; no new identity was created");
                if (previous.Active != 0) throw new PhoneMcpSessionException("device_busy", "An old call is still running; wait for its receipt before resuming");
                clients.Remove(previous.Id);
            }
            if (clients.Count >= 512) throw new PhoneMcpSessionException("device_busy", "MCP session capacity reached");
            var client = new Client(Guid.NewGuid().ToString("N"), binding,
                new string(name.Where(ch => !char.IsControl(ch)).Take(40).ToArray()) is { Length: > 0 } clean ? clean : "Agent");
            if (previous != null) client = client with { LogicalId = previous.LogicalId, ResumeToken = previous.ResumeToken, Generation = previous.Generation + 1, Name = previous.Name };
            clients.Add(client.Id, client); recoverable[client.ResumeToken] = client; return client;
        }
    }
    public Client? Get(string binding, string id) {
        lock (gate) {
            Expire();
            if (!clients.TryGetValue(id, out var client) || client.Binding != binding) return null;
            client.Touched = Environment.TickCount64; return client;
        }
    }
    public Client? Remove(string binding, string id) {
        lock (gate) { if (!clients.TryGetValue(id, out var client) || client.Binding != binding) return null; clients.Remove(id); recoverable.Remove(client.ResumeToken); return client; }
    }
    public string[] RemoveBinding(string binding) {
        lock (gate) {
            var owned = recoverable.Values.Where(client => client.Binding == binding).ToArray();
            foreach (var client in owned) clients.Remove(client.Id);
            foreach (var client in owned) recoverable.Remove(client.ResumeToken);
            return owned.Select(client => client.AgentId).ToArray();
        }
    }
    public IDisposable BeginCall(Client client) {
        lock (gate) {
            if (!clients.TryGetValue(client.Id, out var current) || !ReferenceEquals(current, client)) throw new PhoneMcpSessionException("session_replaced", "This session was replaced by a resumed session");
            client.Active++; client.Touched = Environment.TickCount64;
        }
        return new Lease(() => { lock (gate) { client.Active--; } });
    }
    private sealed class Lease(Action release) : IDisposable { private Action? action = release; public void Dispose() => Interlocked.Exchange(ref action, null)?.Invoke(); }
    private void Expire() {
        foreach (var key in clients.Where(pair => pair.Value.Active == 0 && Environment.TickCount64 - pair.Value.Touched > 30 * 60_000L).Select(pair => pair.Key).ToArray()) clients.Remove(key);
        foreach (var key in recoverable.Where(pair => pair.Value.Active == 0 && Environment.TickCount64 - pair.Value.Touched > 24 * 60 * 60_000L).Select(pair => pair.Key).ToArray()) recoverable.Remove(key);
        foreach (var key in recoverable.Where(pair => pair.Value.Active == 0 && !clients.ContainsKey(pair.Value.Id)).OrderBy(pair => pair.Value.Touched).Take(Math.Max(0, recoverable.Count - 1024)).Select(pair => pair.Key).ToArray()) recoverable.Remove(key);
    }
}
public sealed class PhoneMcpSessionException(string code, string message) : Exception(message) { public string Code { get; } = code; }
