using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

// App-server builds without isPinned need an independent RemoteTool-owned record.
// Keep ids only, under the actual Codex home, so different users never share pins.
internal sealed class CodexSessionPins(string path) {
    private readonly object gate = new();
    private JsonObject entries = Read(path);
    public string[] Ids { get { lock (gate) return entries.Select(pair => pair.Key).ToArray(); } }
    public bool Contains(string id) { lock (gate) return entries.ContainsKey(id); }
    public void Set(string id, bool pinned) {
        lock (gate) {
            if (entries.ContainsKey(id) == pinned) return;
            var updated = entries.DeepClone().AsObject();
            if (pinned) updated[id] = true; else updated.Remove(id);
            // Publish in-memory state only after durable storage succeeds.
            Atomic(path, updated);
            entries = updated;
        }
    }
}
