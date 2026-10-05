using System.Diagnostics;
using System.IO.Pipes;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Win32.SafeHandles;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

/** Compatibility with the installed desktop IPC v1 thread-availability event. */
internal static class CodexDesktopSync
{
    public static async Task<bool> NotifyAvailable(IEnumerable<string> threadIds) {
        return await Notify(threadIds, "thread-unarchived", 1);
    }
    public static Task<bool> NotifyRemoved(IEnumerable<string> threadIds) => Notify(threadIds, "thread-archived", 2);
    public static Task<JsonObject> Compact(string id) => Request("thread-follower-compact-thread", 1, Obj(("conversationId", id)));
    public static Task<JsonObject> StartTurn(string id, JsonObject request) {
        request["threadId"] = id;
        return Request("thread-follower-start-turn", 2, Obj(("conversationId", id),
            ("turnStart", Obj(("request", request), ("context", Obj(("inheritThreadSettings", true)))))));
    }
    public static Task<JsonObject> StopCompact(string id) => Request("thread-follower-interrupt-turn", 3, Obj(("conversationId", id), ("mode", "user-stop")));
    public static Task<JsonObject> SteerTurn(string id, JsonArray input, string key) => Request("thread-follower-steer-turn", 1,
        Obj(("conversationId", id), ("input", input), ("clientUserMessageId", key), ("attachments", new JsonArray()),
            ("restoreMessage", Obj(("id", key), ("cwd", null), ("context", Obj(("workspaceRoots", new JsonArray()))), ("responsesapiClientMetadata", new JsonObject())))));
    public static Task<JsonObject> Reply(string id, JsonObject request, JsonObject response) {
        var method = request.S("method");
        var parameters = Obj(("conversationId", id), ("requestId", request["id"]));
        string action;
        switch (method) {
            case "item/tool/requestUserInput": action = "thread-follower-submit-user-input"; parameters["response"] = response.DeepClone(); break;
            case "item/permissions/requestApproval": action = "thread-follower-permissions-request-approval-response"; parameters["response"] = response.DeepClone(); break;
            case "item/commandExecution/requestApproval": action = "thread-follower-command-approval-decision"; parameters["decision"] = response["decision"]?.DeepClone(); break;
            case "item/fileChange/requestApproval": action = "thread-follower-file-approval-decision"; parameters["decision"] = response["decision"]?.DeepClone(); break;
            default: throw new CodexError("此桌面确认需要在电脑端处理", 409);
        }
        return Request(action, 1, parameters);
    }
    private static async Task<JsonObject> Request(string method, int version, JsonObject parameters) {
        if (!OperatingSystem.IsWindows()) throw new CodexError("桌面会话被其他客户端占用，当前系统不支持桌面协作", 409);
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        using var pipe = new NamedPipeClientStream(".", "codex-ipc", PipeDirection.InOut, PipeOptions.Asynchronous);
        try {
            await pipe.ConnectAsync(timeout.Token);
            if (!Trusted(pipe)) throw new CodexError("无法验证桌面 Codex 连接", 409);
            var clientId = await Initialize(pipe, timeout.Token);
            var requestId = Guid.NewGuid().ToString();
            await Write(pipe, Obj(("type", "request"), ("requestId", requestId), ("sourceClientId", clientId),
                ("method", method), ("version", version), ("params", parameters), ("timeoutMs", 25000)), timeout.Token);
            for (var i = 0; i < 128; i++) {
                var response = await Read(pipe, timeout.Token);
                if (response.S("requestId") != requestId) continue;
                if (response.S("resultType") != "success") throw new CodexError("桌面 Codex 未接受操作：" + response.S("error", "会话所有者暂不可用"), 409);
                return response["result"] as JsonObject ?? new();
            }
            throw new IOException("Desktop response missing");
        } catch (Exception error) when (error is IOException or OperationCanceledException) {
            // A sent operation can have succeeded despite losing the acknowledgement.
            throw new CodexError("桌面操作结果尚未确认，请核对会话状态；不会自动重发", 504);
        }
    }
    public static async IAsyncEnumerable<JsonObject> Follow(string id, [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct) {
        using var pipe = new NamedPipeClientStream(".", "codex-ipc", PipeDirection.InOut, PipeOptions.Asynchronous);
        await pipe.ConnectAsync(ct);
        if (!Trusted(pipe)) throw new IOException("Untrusted desktop pipe");
        var clientId = await Initialize(pipe, ct);
        async Task Following(bool value) => await Write(pipe, Obj(("type", "broadcast"), ("sourceClientId", clientId),
            ("method", "thread-stream-following-changed"), ("version", 1),
            ("params", Obj(("hostId", "local"), ("conversationId", id), ("following", value)))), ct);
        await Following(true);
        JsonObject state = null; long revision = -1;
        while (!ct.IsCancellationRequested) {
            var message = await Read(pipe, ct);
            var p = message["params"];
            if (message.S("method") != "thread-stream-state-changed" || p.S("conversationId") != id || p.S("hostId") != "local") continue;
            var change = p?["change"];
            if (change.S("type") == "snapshot") { state = change?["conversationState"]?.DeepClone() as JsonObject; revision = change.L("revision"); }
            else if (change.S("type") == "patches") {
                if (state == null || revision != change.L("baseRevision")) { await Following(true); continue; }
                try { foreach (var patch in change.A("patches")) ApplyPatch(state, patch); revision = change.L("revision"); }
                catch (Exception error) when (error is ArgumentException or InvalidOperationException or IndexOutOfRangeException) { state = null; await Following(true); continue; }
            }
            if (state != null) yield return state.DeepClone().AsObject();
        }
        // Closing the pipe also removes this transient follower from desktop ownership.
    }
    internal static void ApplyPatch(JsonObject state, JsonNode patch) {
        var path = patch.A("path");
        if (path.Count == 0) throw new InvalidOperationException("Invalid root patch");
        JsonNode parent = state;
        for (var i = 0; i < path.Count - 1; i++) parent = parent is JsonArray a ? a[int.Parse(path[i]!.ToString())] : parent[path[i]!.ToString()];
        var key = path[^1]!.ToString(); var op = patch.S("op");
        if (parent is JsonArray array) {
            var index = int.Parse(key);
            if (op == "remove") array.RemoveAt(index);
            else if (op == "add") array.Insert(index, patch?["value"]?.DeepClone());
            else if (op == "replace") array[index] = patch?["value"]?.DeepClone();
            else throw new InvalidOperationException("Unsupported patch");
        } else if (parent is JsonObject obj) {
            if (op == "remove") obj.Remove(key);
            else if (op is "add" or "replace") obj[key] = patch?["value"]?.DeepClone();
            else throw new InvalidOperationException("Unsupported patch");
        } else throw new InvalidOperationException("Invalid patch parent");
    }
    private static bool Trusted(NamedPipeClientStream pipe) {
        if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out var pid)) return false;
        using var server = Process.GetProcessById((int)pid);
        using var current = Process.GetCurrentProcess();
        if (server.SessionId != current.SessionId) return false;
        var path = server.MainModule?.FileName ?? "";
        return (path.Contains("\\OpenAI.Codex_", StringComparison.OrdinalIgnoreCase) && Path.GetFileName(path).Equals("ChatGPT.exe", StringComparison.OrdinalIgnoreCase)) ||
            (path.Contains("\\OpenAI\\Codex\\", StringComparison.OrdinalIgnoreCase) && Path.GetFileName(path).Equals("node.exe", StringComparison.OrdinalIgnoreCase));
    }
    private static async Task<string> Initialize(Stream pipe, CancellationToken ct) {
        var id = Guid.NewGuid().ToString();
        await Write(pipe, Obj(("type", "request"), ("requestId", id), ("sourceClientId", "initializing-client"),
            ("method", "initialize"), ("version", 0), ("params", Obj(("clientType", "chuckiehelper"))), ("timeoutMs", 1500)), ct);
        for (var i = 0; i < 16; i++) {
            var response = await Read(pipe, ct);
            if (response.S("requestId") != id) continue;
            if (response.S("resultType") == "success" && response.S("method") == "initialize" && response["result"].S("clientId").Length > 0) return response["result"].S("clientId");
            break;
        }
        throw new CodexError("桌面 Codex 连接初始化失败", 409);
    }
    private static async Task<bool> Notify(IEnumerable<string> threadIds, string method, int version) {
        if (!OperatingSystem.IsWindows()) return false;
        var ids = threadIds.Where(id => Guid.TryParse(id, out _)).Distinct().ToArray();
        if (ids.Length == 0) return true;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        try {
            using var pipe = new NamedPipeClientStream(".", "codex-ipc", PipeDirection.InOut, PipeOptions.Asynchronous);
            await pipe.ConnectAsync(timeout.Token);
            if (!GetNamedPipeServerProcessId(pipe.SafePipeHandle, out var pid)) return false;
            using var server = Process.GetProcessById((int)pid);
            using var current = Process.GetCurrentProcess();
            if (server.SessionId != current.SessionId) return false;
            var path = server.MainModule?.FileName ?? "";
            // Never send to a same-named pipe owned by an unrelated program/session.
            if (!(path.Contains("\\OpenAI.Codex_", StringComparison.OrdinalIgnoreCase) && Path.GetFileName(path).Equals("ChatGPT.exe", StringComparison.OrdinalIgnoreCase)) &&
                !(path.Contains("\\OpenAI\\Codex\\", StringComparison.OrdinalIgnoreCase) && Path.GetFileName(path).Equals("node.exe", StringComparison.OrdinalIgnoreCase))) return false;
            var requestId = Guid.NewGuid().ToString();
            await Write(pipe, Obj(("type", "request"), ("requestId", requestId), ("sourceClientId", "initializing-client"),
                ("method", "initialize"), ("version", 0), ("params", Obj(("clientType", "chuckiehelper"))), ("timeoutMs", 1500)), timeout.Token);
            string clientId = null;
            for (var i = 0; i < 16; i++) {
                var response = await Read(pipe, timeout.Token);
                if (response.S("requestId") != requestId) continue;
                if (response.S("resultType") != "success" || response.S("method") != "initialize") return false;
                clientId = response["result"].S("clientId"); break;
            }
            if (string.IsNullOrEmpty(clientId)) return false;
            foreach (var id in ids) await Write(pipe, Obj(("type", "broadcast"), ("method", method), ("version", version),
                ("sourceClientId", clientId), ("params", Obj(("hostId", "local"), ("conversationId", id)))), timeout.Token);
            await pipe.FlushAsync(timeout.Token);
            return true;
        } catch (Exception error) when (error is IOException or OperationCanceledException or UnauthorizedAccessException or InvalidOperationException or ArgumentException or System.ComponentModel.Win32Exception or System.Text.Json.JsonException) {
            // Desktop may be closed or use another IPC protocol. Chat remains usable.
            return false;
        }
    }
    private static async Task Write(Stream stream, JsonObject value, CancellationToken ct) {
        var payload = Encoding.UTF8.GetBytes(value.ToJsonString());
        await stream.WriteAsync(BitConverter.GetBytes(payload.Length), ct);
        await stream.WriteAsync(payload, ct);
    }
    private static async Task<JsonObject> Read(Stream stream, CancellationToken ct) {
        var header = new byte[4]; await stream.ReadExactlyAsync(header, ct);
        var length = BitConverter.ToInt32(header);
        if (length is <= 0 or > 16 * 1024 * 1024) throw new IOException("Invalid desktop IPC frame");
        var payload = new byte[length]; await stream.ReadExactlyAsync(payload, ct);
        return JsonNode.Parse(payload)!.AsObject();
    }
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint pid);
}
