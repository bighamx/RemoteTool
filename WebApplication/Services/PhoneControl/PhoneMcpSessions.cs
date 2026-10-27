namespace RemoteTool.WebApi.Services.PhoneControl;

public sealed class PhoneMcpSessions(Func<long>? clock = null) {
    public const long IdleTimeoutMs = 5 * 60_000L;
    private readonly Func<long> now = clock ?? (() => Environment.TickCount64);
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
    private readonly Dictionary<string, Client> expired = new();
    public Client Create(string binding, string name, string? resumeToken = null) {
        lock (gate) {
            Expire();
            Client? previous = null;
            if (resumeToken != null) {
                if (!recoverable.TryGetValue(resumeToken, out previous) || previous.Binding != binding)
                    throw new PhoneMcpSessionException("resume_unknown", "Recovery credentials are unknown, expired or revoked; no new identity was created");
                if (previous.Active != 0) throw new PhoneMcpSessionException("device_busy", "An old call is still running; wait for its receipt before resuming");
                clients.Remove(previous.Id);
            }
            if (clients.Count >= 512) throw new PhoneMcpSessionException("device_busy", "MCP session capacity reached");
            var client = new Client(Guid.NewGuid().ToString("N"), binding,
                new string(name.Where(ch => !char.IsControl(ch)).Take(40).ToArray()) is { Length: > 0 } clean ? clean : "Agent");
            if (previous != null) client = client with { LogicalId = previous.LogicalId, ResumeToken = previous.ResumeToken, Generation = previous.Generation + 1 };
            client.Touched = now();
            expired.Remove(client.AgentId);
            clients.Add(client.Id, client); recoverable[client.ResumeToken] = client; return client;
        }
    }
    public Client? Get(string binding, string id) {
        lock (gate) {
            Expire();
            if (!clients.TryGetValue(id, out var client) || client.Binding != binding) return null;
            client.Touched = now(); return client;
        }
    }
    public Client? Remove(string binding, string id) {
        lock (gate) {
            if (!clients.TryGetValue(id, out var client) || client.Binding != binding) return null;
            return RemoveOwned(client);
        }
    }
    public Client? RemoveByResumeToken(string binding, string token) {
        lock (gate) {
            Expire();
            if (!recoverable.TryGetValue(token, out var client) || client.Binding != binding) return null;
            return RemoveOwned(client);
        }
    }
    private Client RemoveOwned(Client client) {
        if (client.Active != 0) throw new PhoneMcpSessionException("device_busy", "An operation is running; wait for its receipt before closing this owner");
        clients.Remove(client.Id); recoverable.Remove(client.ResumeToken); expired.Remove(client.AgentId); return client;
    }
    public string[] RemoveBinding(string binding) {
        lock (gate) {
            var owned = recoverable.Values.Where(client => client.Binding == binding).ToArray();
            foreach (var client in owned) clients.Remove(client.Id);
            foreach (var client in owned) recoverable.Remove(client.ResumeToken);
            foreach (var client in owned) expired.Remove(client.AgentId);
            return owned.Select(client => client.AgentId).ToArray();
        }
    }
    public IDisposable BeginCall(Client client) {
        lock (gate) {
            if (!clients.TryGetValue(client.Id, out var current) || !ReferenceEquals(current, client)) throw new PhoneMcpSessionException("session_replaced", "This session was replaced by a resumed session");
            client.Active++; client.Touched = now();
        }
        return new Lease(() => { lock (gate) { client.Active--; client.Touched = now(); } });
    }
    public Client[] DrainExpired() {
        lock (gate) { Expire(); var result = expired.Values.ToArray(); expired.Clear(); return result; }
    }
    public bool HasLiveOwner(string agentId) { lock (gate) { return clients.Values.Any(client => client.AgentId == agentId); } }
    private sealed class Lease(Action release) : IDisposable { private Action? action = release; public void Dispose() => Interlocked.Exchange(ref action, null)?.Invoke(); }
    private void Expire() {
        foreach (var client in clients.Values.Where(client => client.Active == 0 && now() - client.Touched >= IdleTimeoutMs).ToArray()) {
            clients.Remove(client.Id); expired[client.AgentId] = client;
        }
        foreach (var key in recoverable.Where(pair => pair.Value.Active == 0 && now() - pair.Value.Touched > 24 * 60 * 60_000L).Select(pair => pair.Key).ToArray()) recoverable.Remove(key);
        foreach (var key in recoverable.Where(pair => pair.Value.Active == 0 && !clients.ContainsKey(pair.Value.Id)).OrderBy(pair => pair.Value.Touched).Take(Math.Max(0, recoverable.Count - 1024)).Select(pair => pair.Key).ToArray()) recoverable.Remove(key);
    }
}
public sealed class PhoneMcpSessionException(string code, string message) : Exception(message) { public string Code { get; } = code; }
