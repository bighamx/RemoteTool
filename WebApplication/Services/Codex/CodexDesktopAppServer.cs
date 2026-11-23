using System.Diagnostics;
using System.Net;
using System.Net.WebSockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

/** Compatibility with desktop builds that expose AppServerManager instead of the v1 IPC broker. */
internal sealed class CodexDesktopAppServer
{
    internal const string Bootstrap = """
        (async () => {
            const key = '__remoteToolDesktopAppServer';
            if (window[key]?.revision === 2) return true;
            let scope;
            for (const node of document.querySelectorAll('[data-app-action-sidebar-thread-id], [data-codex-composer-root], main, aside')) {
                const fiberKey = Object.getOwnPropertyNames(node).find(name => name.startsWith('__reactFiber$'));
                for (let fiber = node[fiberKey], depth = 0; fiber && depth < 40; fiber = fiber.return, depth++) {
                    for (let hook = fiber.memoizedState, index = 0; hook && index < 64; hook = hook.next, index++) {
                        const candidate = hook.memoizedState?.current;
                        if (candidate && typeof candidate.get === 'function' && candidate.node) { scope = candidate; break; }
                    }
                    if (scope) break;
                }
                if (scope) break;
            }
            if (!scope) return false;
            let client;
            function inspect(exports) {
                for (const atom of Object.values(exports || {})) {
                    if (!atom || !['object', 'function'].includes(typeof atom)) continue;
                    let manager;
                    try { manager = scope.get(atom); } catch { continue; }
                    if (typeof manager?.forHost === 'function') { client = manager.forHost('local'); return true; }
                }
                return false;
            }
            const urls = [...new Set([
                ...Array.from(document.scripts, script => script.src),
                ...Array.from(document.querySelectorAll('link[href]'), link => link.href),
                ...performance.getEntriesByType('resource').map(entry => entry.name)
            ])].filter(url => url.startsWith('app://-/assets/') && url.split('?')[0].endsWith('.js'))
                .sort((a, b) => Number(!/app-shared|app-initial|app-main|app-primary/.test(a)) - Number(!/app-shared|app-initial|app-main|app-primary/.test(b)));
            for (const url of urls.slice(0, 24)) {
                let module;
                try { module = await import(url); } catch { continue; }
                if (inspect(module)) break;
                const runtime = module.__webpack_require__;
                if (!runtime?.c || !runtime?.m) continue;
                for (const [id, factory] of Object.entries(runtime.m)) {
                    if (typeof factory !== 'function' || !runtime.c[id]) continue;
                    const source = Function.prototype.toString.call(factory);
                    if (source.includes('AppServerManager RPC is unavailable for hostId:') && source.includes('.forHost(') && inspect(runtime.c[id].exports)) break;
                }
                if (client) break;
            }
            if (!client) return false;
            window[key] = async (method, params) => {
                const id = params.conversationId;
                const state = await client.getConversation(id);
                if (!state) return { available: false };
                if (method === 'probe') return { available: true, rolloutPath: state.rolloutPath };
                if (method === 'prepare') {
                    if (await client.hasLocalHistorySubscription(id)) return { ready: true };
                    const result = await client.resumeConversation({ conversationId: id, intent: 'view',
                        workspaceRoots: state.cwd ? [state.cwd] : [], preserveServerConfiguration: true });
                    return { ready: result.status === 'ready' };
                }
                if (method === 'snapshot') {
                    const history = state.turnHistory?.history?.entitiesByKey;
                    const turns = [...(state.turns || []), ...Object.values(history || {})];
                    if (turns.length === 0) {
                        const page = await client.sendRequest('thread/turns/list', { threadId: id, limit: 2, itemsView: 'full', sortDirection: 'desc' });
                        turns.push(...(page.data || []).reverse().map(turn => ({
                            ...turn, turnId: turn.id,
                            turnStartedAtMs: turn.startedAt == null ? undefined : turn.startedAt * 1000,
                            items: (turn.items || []).map(item => ({ ...item, completed: item.completedAtMs != null }))
                        })));
                    }
                    const selected = [...new Map(turns.map(turn => [turn.turnId, turn])).values()]
                        .sort((a, b) => Number(a.status === 'inProgress') - Number(b.status === 'inProgress') || (a.turnStartedAtMs || 0) - (b.turnStartedAtMs || 0)).slice(-2);
                    return { turns: selected, requests: state.requests || [], latestTokenUsageInfo: state.latestTokenUsageInfo };
                }
                try {
                    let result;
                    switch (method) {
                        case 'thread-follower-start-turn':
                            result = await client.startTurn(id, { ...params.turnStart,
                                request: { ...params.turnStart.request, threadId: id } });
                            return { result };
                        case 'thread-follower-steer-turn': {
                            const turns = [...(state.turns || []), ...Object.values(state.turnHistory?.history?.entitiesByKey || {})];
                            const turn = turns.find(turn => turn.status === 'inProgress');
                            if (!turn) return { rejected: true, message: '当前任务已结束，请刷新会话后发送' };
                            return { result: await client.sendRequest('turn/steer', { threadId: id, input: params.input, expectedTurnId: turn.turnId, clientUserMessageId: params.clientUserMessageId }) };
                        }
                        case 'thread-follower-compact-thread': result = await client.compactThread(id); break;
                        case 'thread-follower-interrupt-turn': result = await client.interruptConversation(id, params.mode, params.expectedTurnId); break;
                        case 'thread-follower-command-approval-decision': result = await client.replyWithCommandExecutionApprovalDecision(id, params.requestId, params.decision); break;
                        case 'thread-follower-file-approval-decision': result = await client.replyWithFileChangeApprovalDecision(id, params.requestId, params.decision); break;
                        case 'thread-follower-permissions-request-approval-response': result = await client.replyWithPermissionsRequestApprovalResponse(id, params.requestId, params.response); break;
                        case 'thread-follower-submit-user-input': result = await client.replyWithUserInputResponse(id, params.requestId, params.response); break;
                        default: return { rejected: true, message: '此桌面操作尚不受支持' };
                    }
                    return { result: result ?? {} };
                } catch (error) {
                    return { failed: true, rejected: error?.delivery?.stage === 'not-sent', message: String(error?.message || error) };
                }
            };
            window[key].revision = 2;
            client.onRpcBroken(() => { delete window[key]; });
            return true;
        })()
        """;

    public static async Task<JsonObject> Request(string home, string method, JsonObject parameters) {
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(30));
        var dispatched = false;
        try {
            using var connection = await Open(home, parameters.S("conversationId"), timeout.Token);
            var preparation = await connection.Evaluate(Invocation("prepare", parameters), timeout.Token);
            if (!preparation.B("ready")) throw new CodexError("桌面会话尚未就绪，本次操作未投递。请在桌面打开该会话后重试。", 409, "session_owner_unavailable", "rejected");
            dispatched = true;
            var result = (await connection.Evaluate(Invocation(method, parameters), timeout.Token))?.AsObject() ?? new();
            if (result.B("rejected") || result.ContainsKey("available"))
                throw new CodexError(result.S("message", "桌面会话尚未就绪，请在桌面打开该会话后重试"), 409, "session_owner_unavailable", "rejected");
            if (result.B("failed")) throw new CodexError("桌面操作结果尚未确认，请核对会话状态；不会自动重发", 504, "delivery_unknown");
            return result;
        } catch (Exception error) when (error is IOException or OperationCanceledException or WebSocketException or System.Text.Json.JsonException) {
            throw new CodexError(dispatched ? "桌面操作结果尚未确认，请核对会话状态；不会自动重发" :
                "桌面协作连接不可用，本次消息未写入。请确认桌面本地协作连接已启用后重试。", dispatched ? 504 : 409,
                dispatched ? "delivery_unknown" : "session_owner_unavailable", dispatched ? "unknown" : "rejected");
        }
    }

    public static async IAsyncEnumerable<JsonObject> Follow(string home, string id,
        [System.Runtime.CompilerServices.EnumeratorCancellation] CancellationToken ct) {
        using var connection = await Open(home, id, ct);
        while (!ct.IsCancellationRequested) {
            var result = await connection.Evaluate(Invocation("snapshot", Obj(("conversationId", id))), ct);
            if (result is not JsonObject snapshot || snapshot.ContainsKey("available")) throw new IOException("Desktop conversation unavailable");
            yield return snapshot;
            await Task.Delay(250, ct);
        }
    }

    private static string Invocation(string method, JsonObject parameters) =>
        "window.__remoteToolDesktopAppServer(" + System.Text.Json.JsonSerializer.Serialize(method) + "," + parameters.ToJsonString() + ")";

    private static async Task<Connection> Open(string home, string id, CancellationToken ct) {
        foreach (var port in DesktopPorts()) {
            using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(2) };
            JsonArray targets;
            try { targets = JsonNode.Parse(await http.GetStringAsync($"http://127.0.0.1:{port}/json/list", ct)) as JsonArray; }
            catch (Exception error) when (!ct.IsCancellationRequested && error is HttpRequestException or System.Text.Json.JsonException or OperationCanceledException) { continue; }
            foreach (var target in targets ?? new JsonArray()) {
                if (target.S("type") != "page" || target.S("url") != "app://-/index.html" ||
                    !Uri.TryCreate(target.S("webSocketDebuggerUrl"), UriKind.Absolute, out var uri) ||
                    uri.Scheme != "ws" || uri.Host != "127.0.0.1" || uri.Port != port) continue;
                var connection = new Connection();
                try {
                    await connection.Connect(uri, ct);
                    if ((await connection.Evaluate(Bootstrap, ct))?.GetValue<bool>() != true) { connection.Dispose(); continue; }
                    var probe = await connection.Evaluate(Invocation("probe", Obj(("conversationId", id))), ct);
                    var path = probe.S("rolloutPath");
                    if (!probe.B("available") || !MatchesHome(home, path)) {
                        connection.Dispose(); continue;
                    }
                    return connection;
                } catch (Exception error) when (error is IOException or WebSocketException or System.Text.Json.JsonException) { connection.Dispose(); }
                catch { connection.Dispose(); throw; }
            }
        }
        throw new IOException("No compatible desktop app-server connection");
    }

    internal static bool MatchesHome(string home, string path) {
        static string Normalize(string value) {
            if (value.StartsWith(@"\\?\UNC\", StringComparison.OrdinalIgnoreCase)) value = @"\\" + value[8..];
            else if (value.StartsWith(@"\\?\", StringComparison.Ordinal)) value = value[4..];
            return Path.GetFullPath(value).TrimEnd(Path.DirectorySeparatorChar);
        }
        return Path.IsPathFullyQualified(path) && Normalize(path).StartsWith(Normalize(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase);
    }

    private static IEnumerable<int> DesktopPorts() {
        if (!OperatingSystem.IsWindows()) yield break;
        var size = 0;
        GetExtendedTcpTable(IntPtr.Zero, ref size, false, 2, 3, 0);
        var buffer = Marshal.AllocHGlobal(size);
        var ports = new HashSet<int>();
        using var current = Process.GetCurrentProcess();
        try {
            if (GetExtendedTcpTable(buffer, ref size, false, 2, 3, 0) != 0) yield break;
            var count = Marshal.ReadInt32(buffer);
            for (var index = 0; index < count; index++) {
                var offset = 4 + index * 24;
                if (Marshal.ReadInt32(buffer, offset) != 2 || Marshal.ReadInt32(buffer, offset + 4) != 0x0100007f) continue;
                try {
                    using var process = Process.GetProcessById(Marshal.ReadInt32(buffer, offset + 20));
                    if (!CodexDesktopSync.TrustedWindowsProcess(process.MainModule?.FileName ?? "", process.SessionId, current.SessionId)) continue;
                    ports.Add((ushort)IPAddress.NetworkToHostOrder((short)Marshal.ReadInt32(buffer, offset + 8)));
                } catch (Exception error) when (error is ArgumentException or InvalidOperationException or System.ComponentModel.Win32Exception) { }
            }
        } finally { Marshal.FreeHGlobal(buffer); }
        foreach (var port in ports) yield return port;
    }

    internal sealed class Connection : IDisposable
    {
        private readonly ClientWebSocket socket = new();
        private int sequence;
        public async Task Connect(Uri uri, CancellationToken ct) {
            try { await socket.ConnectAsync(uri, ct); }
            catch (WebSocketException error) { throw new IOException("Desktop connection failed", error); }
        }
        public async Task<JsonNode> Evaluate(string expression, CancellationToken ct) {
            try { return await EvaluateCore(expression, ct); }
            catch (WebSocketException error) { throw new IOException("Desktop connection interrupted", error); }
        }
        private async Task<JsonNode> EvaluateCore(string expression, CancellationToken ct) {
            var id = ++sequence;
            var request = Obj(("id", id), ("method", "Runtime.evaluate"),
                ("params", Obj(("expression", expression), ("awaitPromise", true), ("returnByValue", true))));
            await socket.SendAsync(Encoding.UTF8.GetBytes(request.ToJsonString()), WebSocketMessageType.Text, true, ct);
            var buffer = new byte[16384];
            while (true) {
                using var frame = new MemoryStream();
                WebSocketReceiveResult received;
                do {
                    received = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                    if (received.MessageType == WebSocketMessageType.Close || frame.Length + received.Count > 16 * 1024 * 1024) throw new IOException("Desktop connection closed or frame too large");
                    frame.Write(buffer, 0, received.Count);
                } while (!received.EndOfMessage);
                var response = JsonNode.Parse(frame.ToArray());
                if (response.L("id") != id) continue;
                if (response?["error"] != null || response?["result"]?["exceptionDetails"] != null) throw new IOException("Desktop evaluation failed");
                return response?["result"]?["result"]?["value"];
            }
        }
        public void Dispose() => socket.Dispose();
    }

    [DllImport("iphlpapi.dll", SetLastError = true)]
    private static extern uint GetExtendedTcpTable(IntPtr table, ref int size, bool order, int family, int tableClass, uint reserved);
}
