using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

/** Explicit per-session policy, independent of model choices and global config.toml. */
internal sealed class CodexSessionPermissions(string path)
{
    private readonly object gate = new();
    private JsonObject modes = Read(path);
    public string Get(string id) { lock (gate) return modes.S(id, "default"); }
    public void Set(string id, string mode) {
        if (mode is not ("workspace" or "full")) throw new CodexError("无效权限模式");
        lock (gate) {
            var updated = modes.DeepClone().AsObject(); updated[id] = mode;
            Atomic(path, updated); modes = updated;
        }
    }
    public void Remove(string id) {
        lock (gate) {
            if (!modes.ContainsKey(id)) return;
            var updated = modes.DeepClone().AsObject(); updated.Remove(id);
            Atomic(path, updated); modes = updated;
        }
    }
    public void ApplyTurn(string id, JsonObject request) {
        Apply(Get(id), request);
    }
    public static void Apply(string mode, JsonObject request) {
        if (mode == "default") return;
        request["approvalPolicy"] = mode == "full" ? "never" : "on-request";
        request["sandboxPolicy"] = Obj(("type", mode == "full" ? "dangerFullAccess" : "workspaceWrite"));
    }
}
