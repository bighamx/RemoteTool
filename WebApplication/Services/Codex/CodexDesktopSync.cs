using System.Diagnostics;
using System.IO.Pipes;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Win32.SafeHandles;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

/** Compatibility with the installed desktop IPC v1 thread-availability event. */
internal sealed class CodexDesktopSync(string home)
{
    public async Task<bool> NotifyAvailable(IEnumerable<string> threadIds) {
        return await Notify(threadIds, "thread-unarchived", 1);
    }
    public Task<bool> NotifyRemoved(IEnumerable<string> threadIds) => Notify(threadIds, "thread-archived", 2);
    public Task<JsonObject> Compact(string id) => Request("thread-follower-compact-thread", 1, Obj(("conversationId", id)));
    public Task<JsonObject> StartTurn(string id, JsonObject request) {
        request["threadId"] = id;
        return Request("thread-follower-start-turn", 2, Obj(("conversationId", id),
            ("turnStart", Obj(("request", request), ("context", Obj(("inheritThreadSettings", true)))))));
    }
    public Task<JsonObject> StopCompact(string id, string expectedTurnId = null) => Request("thread-follower-interrupt-turn", expectedTurnId == null ? 3 : 4, Obj(("conversationId", id), ("mode", "user-stop"), ("expectedTurnId", expectedTurnId)));
    public Task<JsonObject> SteerTurn(string id, JsonArray input, string key) => Request("thread-follower-steer-turn", 1,
        Obj(("conversationId", id), ("input", input), ("clientUserMessageId", key), ("attachments", new JsonArray()),
            ("restoreMessage", Obj(("id", key), ("cwd", null), ("context", Obj(("workspaceRoots", new JsonArray()))), ("responsesapiClientMetadata", new JsonObject())))));
    public Task<JsonObject> Reply(string id, JsonObject request, JsonObject response) {
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
    private Task<JsonObject> Request(string method, int version, JsonObject parameters) {
        if (!OperatingSystem.IsMacOS()) return RequestCore(method, version, parameters);
        return RecoverOwner(parameters.S("conversationId"),
            () => RequestCore(method, version, parameters), OpenThread,
            id => RequestCore("thread-owner-discovery", 1, Obj(("hostId", "local"), ("conversationId", id))));
    }
    internal static async Task<JsonObject> RecoverOwner(string id, Func<Task<JsonObject>> request,
        Func<string, Task> open, Func<string, Task<JsonObject>> discover) {
        try { return await request(); }
        catch (CodexError error) when (error.Code == "desktop_no_client" && Guid.TryParseExact(id, "D", out _)) {
            // The broker explicitly rejected routing: no owner received the operation.
            await open(id);
            for (var attempt = 0; ; attempt++) {
                await Task.Delay(250);
                try { await discover(id); break; }
                catch (CodexError unavailable) when (unavailable.Code == "desktop_no_client" && attempt < 1) { }
            }
            // A timeout or disconnect must never enter this retry path.
            return await request();
        }
    }
    private static async Task OpenThread(string id) {
        try {
            using var process = Process.Start(new ProcessStartInfo("/usr/bin/open") {
                UseShellExecute = false, ArgumentList = { "-g", "codex://threads/" + id }
            });
            using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(3));
            if (process == null) throw new IOException("Cannot open desktop thread");
            await process.WaitForExitAsync(timeout.Token);
            if (process.ExitCode != 0) throw new IOException("Desktop rejected thread URL");
        } catch (Exception error) when (error is IOException or OperationCanceledException or System.ComponentModel.Win32Exception) {
            throw new CodexError("桌面会话尚未加载，本次消息未写入。请在 Mac Codex 中打开该会话后重试。", 409, "session_owner_unavailable");
        }
    }
    private async Task<JsonObject> RequestCore(string method, int version, JsonObject parameters) {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        var dispatched = false;
        try {
            using var connect = CancellationTokenSource.CreateLinkedTokenSource(timeout.Token);
            connect.CancelAfter(TimeSpan.FromSeconds(2));
            using var pipe = await Connect(connect.Token);
            var clientId = await Initialize(pipe, timeout.Token);
            var requestId = Guid.NewGuid().ToString();
            dispatched = true;
            await Write(pipe, Obj(("type", "request"), ("requestId", requestId), ("sourceClientId", clientId),
                ("method", method), ("version", version), ("params", parameters), ("timeoutMs", 25000)), timeout.Token);
            for (var i = 0; i < 128; i++) {
                var response = await Read(pipe, timeout.Token);
                if (response.S("requestId") != requestId) continue;
                if (response.S("resultType") != "success") throw new CodexError("桌面 Codex 未接受操作：" + response.S("error", "会话所有者暂不可用"), 409,
                    response.S("error") == "no-client-found" ? "desktop_no_client" : "");
                return response["result"] as JsonObject ?? new();
            }
            throw new IOException("Desktop response missing");
        } catch (Exception error) when (error is IOException or OperationCanceledException) {
            // A sent operation can have succeeded despite losing the acknowledgement.
            if (!dispatched) throw new CodexError("持有会话的桌面客户端无法访问，本次消息未写入。请退出原客户端后重试。", 409, "session_owner_unavailable");
            throw new CodexError("桌面操作结果尚未确认，请核对会话状态；不会自动重发", 504, "delivery_unknown");
        }
    }
    public async IAsyncEnumerable<JsonObject> Follow(string id, [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct) {
        using var pipe = await Connect(ct);
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
    private async Task<Stream> Connect(CancellationToken ct) {
        if (OperatingSystem.IsWindows()) {
            var pipe = new NamedPipeClientStream(".", "codex-ipc", PipeDirection.InOut, PipeOptions.Asynchronous);
            try {
                await pipe.ConnectAsync(ct);
                if (!Trusted(pipe)) throw new IOException("Untrusted desktop pipe");
                return pipe;
            } catch { pipe.Dispose(); throw; }
        }
        if (!OperatingSystem.IsMacOS()) throw new IOException("Desktop collaboration is unsupported on this platform");
        var socket = new Socket(AddressFamily.Unix, SocketType.Stream, ProtocolType.Unspecified);
        try {
            await socket.ConnectAsync(new UnixDomainSocketEndPoint(Path.Combine(home, "ipc", "ipc.sock")), ct);
            if (!TrustedMac(socket)) throw new IOException("Untrusted desktop socket");
            return new NetworkStream(socket, ownsSocket: true);
        } catch (SocketException error) { socket.Dispose(); throw new IOException("Desktop socket unavailable", error); }
        catch { socket.Dispose(); throw; }
    }
    private static bool TrustedMac(Socket socket) {
        var fd = socket.Handle.ToInt32();
        uint size = sizeof(int);
        // LOCAL_PEERPID and getpeereid bind trust to the connected process, not a socket filename.
        if (getpeereid(fd, out var uid, out _) != 0 || uid != geteuid() ||
            getsockopt(fd, 0, 2, out var pid, ref size) != 0 || size != sizeof(int)) return false;
        using var server = Process.GetProcessById(pid);
        var path = server.MainModule?.FileName ?? "";
        return path.EndsWith("/ChatGPT.app/Contents/MacOS/ChatGPT", StringComparison.Ordinal) ||
            path.EndsWith("/Codex.app/Contents/MacOS/Codex", StringComparison.Ordinal);
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
    private async Task<bool> Notify(IEnumerable<string> threadIds, string method, int version) {
        var ids = threadIds.Where(id => Guid.TryParse(id, out _)).Distinct().ToArray();
        if (ids.Length == 0) return true;
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
        try {
            using var pipe = await Connect(timeout.Token);
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
        while (true) {
            var header = new byte[4]; await stream.ReadExactlyAsync(header, ct);
            var length = BitConverter.ToInt32(header);
            if (length is <= 0 or > 16 * 1024 * 1024) throw new IOException("Invalid desktop IPC frame");
            var payload = new byte[length]; await stream.ReadExactlyAsync(payload, ct);
            var message = JsonNode.Parse(payload)!.AsObject();
            if (message.S("type") != "client-discovery-request") return message;
            // Transient RemoteTool clients never own desktop threads.
            await Write(stream, Obj(("type", "client-discovery-response"), ("requestId", message.S("requestId")),
                ("response", Obj(("canHandle", false)))), ct);
        }
    }
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool GetNamedPipeServerProcessId(SafePipeHandle pipe, out uint pid);
    [DllImport("libc", SetLastError = true)] private static extern int getpeereid(int socket, out uint uid, out uint gid);
    [DllImport("libc")] private static extern uint geteuid();
    [DllImport("libc", SetLastError = true)] private static extern int getsockopt(int socket, int level, int option, out int value, ref uint length);
}
