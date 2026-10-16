using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

// thread/metadata/update 会改掉归属，但官方 thread/list 索引只记创建时的归属，
// 移动过的会话随后会从列表（含按项目的分路查询）里消失 —— 而 App/桌面端都指望
// 列表还能看到它。这里记下移动过的 id，列表装配时按 id 回填，保证移动不等于丢失。
internal sealed class CodexSessionMoves(string path) {
    private readonly object gate = new();
    private JsonObject entries = Read(path);
    public string[] Ids { get { lock (gate) return entries.Select(pair => pair.Key).ToArray(); } }
    public void Set(string id, string projectId) {
        lock (gate) {
            var updated = entries.DeepClone().AsObject();
            updated[id] = projectId ?? "";
            // Publish in-memory state only after durable storage succeeds.
            Atomic(path, updated);
            entries = updated;
        }
    }
    public void Remove(string id) {
        lock (gate) {
            if (!entries.ContainsKey(id)) return;
            var updated = entries.DeepClone().AsObject();
            updated.Remove(id);
            Atomic(path, updated);
            entries = updated;
        }
    }
}
