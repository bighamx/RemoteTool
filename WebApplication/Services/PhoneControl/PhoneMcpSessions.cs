namespace RemoteTool.WebApi.Services.PhoneControl;

public sealed class PhoneMcpSessions {
    public sealed record Client(string Id, string Binding, string Name) {
        public string AgentId => Binding + ":" + Id;
        internal long Touched = Environment.TickCount64;
    }
    private readonly object gate = new();
    private readonly Dictionary<string, Client> clients = new();
    public Client Create(string binding, string name) {
        lock (gate) {
            Expire();
            if (clients.Count >= 512) throw new InvalidOperationException("MCP session capacity reached");
            var client = new Client(Guid.NewGuid().ToString("N"), binding,
                new string(name.Where(ch => !char.IsControl(ch)).Take(40).ToArray()) is { Length: > 0 } clean ? clean : "Agent");
            clients.Add(client.Id, client); return client;
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
        lock (gate) { if (!clients.TryGetValue(id, out var client) || client.Binding != binding) return null; clients.Remove(id); return client; }
    }
    public string[] RemoveBinding(string binding) {
        lock (gate) {
            var owned = clients.Values.Where(client => client.Binding == binding).ToArray();
            foreach (var client in owned) clients.Remove(client.Id);
            return owned.Select(client => client.AgentId).ToArray();
        }
    }
    private void Expire() {
        foreach (var key in clients.Where(pair => Environment.TickCount64 - pair.Value.Touched > 30 * 60_000L).Select(pair => pair.Key).ToArray()) clients.Remove(key);
    }
}
