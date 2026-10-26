using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.RemoteControl;

namespace RemoteTool.WebApi.Services.PhoneControl;

public sealed class PhoneOfflineException() : InvalidOperationException("Phone is offline");

/// <summary>Optional transport adapter. All execution and final deduplication live on Android.</summary>
public sealed class PhoneGateway {
    private sealed class Connection(WebSocket socket, JsonObject hello) {
        public readonly WebSocket Socket = socket;
        public volatile JsonObject Hello = hello;
        public readonly SemaphoreSlim SendLock = new(1, 1);
        public readonly ConcurrentDictionary<string, TaskCompletionSource<JsonObject>> Pending = new();
    }
    private readonly ConcurrentDictionary<string, Connection> phones = new();
    private readonly ConcurrentDictionary<string, (string Name, long At)> agentNames = new();
    private readonly object diskLock = new();
    private readonly string folder;
    private (long Written, long Created, long Length)? bindingsStamp;
    private JsonObject? cachedBindings;
    public PhoneMcpSessions McpSessions { get; } = new();
    public PhoneGateway() : this(Path.Combine(RemoteToolPaths.ProgramData, "phone-tools")) { }
    public PhoneGateway(string dataDirectory) { folder = dataDirectory; }
    // All callers hold diskLock. Check replacement metadata so another host process
    // can revoke a binding without waiting for an in-memory cache TTL.
    private JsonObject Read(string name) {
        var path = Path.Combine(folder, name);
        if (name != "bindings.json") return File.Exists(path) ? JsonNode.Parse(File.ReadAllText(path))!.AsObject() : new();
        var info = new FileInfo(path);
        var stamp = info.Exists ? (info.LastWriteTimeUtc.Ticks, info.CreationTimeUtc.Ticks, info.Length) : (0L, 0L, 0L);
        if (cachedBindings == null || bindingsStamp != stamp) {
            cachedBindings = info.Exists ? JsonNode.Parse(File.ReadAllText(path))!.AsObject() : new(); bindingsStamp = stamp;
        }
        return cachedBindings.DeepClone().AsObject();
    }
    private void Save(string name, JsonObject data) {
        Directory.CreateDirectory(folder); var target = Path.Combine(folder, name); var temp = target + ".tmp-" + Guid.NewGuid().ToString("N");
        try { File.WriteAllText(temp, data.ToJsonString()); File.Move(temp, target, true); } finally { if (File.Exists(temp)) File.Delete(temp); }
        if (name == "bindings.json") { cachedBindings = null; bindingsStamp = null; }
    }
    public static string Digest(string value) => Convert.ToHexString(SHA256.HashData(System.Text.Encoding.UTF8.GetBytes(value))).ToLowerInvariant();
    public JsonArray Devices() => new(phones.Select(pair => (JsonNode)new JsonObject {
        ["id"] = pair.Key, ["name"] = pair.Value.Hello["device"]?["name"]?.ToString() ?? pair.Key,
        ["online"] = true, ["capabilities"] = pair.Value.Hello["capabilities"]?.DeepClone()
    }).ToArray());
    public JsonArray Tools(string phone, bool advanced) {
        if (!phones.TryGetValue(phone, out var connection)) throw new PhoneOfflineException();
        return new JsonArray((connection.Hello["tools"]?.AsArray() ?? new()).OfType<JsonObject>()
            .Where(t => advanced || t["annotations"]?["destructiveHint"]?.GetValue<bool>() != true).Select(t => t.DeepClone()).ToArray());
    }
    public async Task Attach(WebSocket socket, CancellationToken ct) {
        Connection? connection = null; string phone = "";
        try {
            using var handshake = CancellationTokenSource.CreateLinkedTokenSource(ct); handshake.CancelAfter(TimeSpan.FromSeconds(10));
            var raw = await WebSocketTextReader.Read(socket, new byte[8192], handshake.Token, 256 * 1024);
            var hello = JsonNode.Parse(raw ?? "{}")!.AsObject(); phone = hello["phone_id"]?.ToString() ?? "";
            var token = hello["phone_token"]?.ToString() ?? "";
            if (hello["type"]?.ToString() != "hello" || !System.Text.RegularExpressions.Regex.IsMatch(phone, "^phone-[a-zA-Z0-9-]{1,100}$") || token.Length < 32) throw new InvalidDataException("Invalid phone hello");
            lock (diskLock) {
                var pairs = Read("pairings.json"); var hash = Digest(token);
                if (pairs[phone] is { } known && known.ToString() != hash) throw new InvalidDataException("Phone pairing changed; remove old pairing first");
                pairs[phone] = hash; Save("pairings.json", pairs);
            }
            hello.Remove("phone_token"); connection = new Connection(socket, hello);
            if (phones.TryGetValue(phone, out var previous)) previous.Socket.Abort();
            phones[phone] = connection;
            await Send(connection, new JsonObject { ["type"] = "ready" }, ct);
            while (socket.State == WebSocketState.Open && !ct.IsCancellationRequested) {
                var text = await WebSocketTextReader.Read(socket, new byte[8192], ct, 8 * 1024 * 1024); if (text == null) break;
                var result = JsonNode.Parse(text)?.AsObject();
                if (result?["type"]?.ToString() == "capabilities" && result["tools"] is JsonArray advertised && result["capabilities"] is JsonObject capabilities) {
                    var updated = connection.Hello.DeepClone().AsObject(); updated["tools"] = advertised.DeepClone(); updated["capabilities"] = capabilities.DeepClone(); connection.Hello = updated;
                }
                if (result?["type"]?.ToString() == "result" && connection.Pending.TryRemove(result["request_id"]?.ToString() ?? "", out var completion))
                    completion.TrySetResult(result["result"]!.DeepClone().AsObject());
            }
        } catch (Exception error) when (error is WebSocketException or OperationCanceledException or InvalidDataException or System.Text.Json.JsonException) { }
        finally {
            if (connection != null) {
                ((ICollection<KeyValuePair<string, Connection>>)phones).Remove(new(phone, connection));
                foreach (var pending in connection.Pending.Values) pending.TrySetException(new IOException("delivery_unknown: phone disconnected; observe before retrying"));
            }
            socket.Abort();
        }
    }
    private static async Task Send(Connection connection, JsonObject message, CancellationToken ct) {
        await connection.SendLock.WaitAsync(ct);
        try { await connection.Socket.SendAsync(System.Text.Encoding.UTF8.GetBytes(message.ToJsonString()), WebSocketMessageType.Text, true, ct); }
        finally { connection.SendLock.Release(); }
    }
    public string AgentName(string id, string fallback = "Agent") => agentNames.TryGetValue(id, out var value) ? value.Name : fallback;
    public async Task RevokeBinding(string id, CancellationToken ct) {
        string? phone;
        lock (diskLock) { phone = Read("bindings.json")[id]?["phone_id"]?.ToString(); }
        var agents = McpSessions.RemoveBinding(id).Append(id).ToArray();
        RemoveBinding(id); foreach (var agent in agents) agentNames.TryRemove(agent, out _);
        if (phone == null || !phones.TryGetValue(phone, out var connection)) return;
        try { foreach (var agent in agents) await Send(connection, new JsonObject { ["type"] = "agent_presence", ["agent_id"] = agent, ["connected"] = false }, ct); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception error) when (error is WebSocketException or IOException or OperationCanceledException or ObjectDisposedException) { }
    }
    public async Task DisconnectAgent(string phone, string id, CancellationToken ct) {
        agentNames.TryRemove(id, out _);
        if (phones.TryGetValue(phone, out var connection))
            await Send(connection, new JsonObject { ["type"]="agent_presence", ["agent_id"]=id, ["connected"]=false }, ct);
    }
    public async Task AgentSeen(string phone, string id, string? name, CancellationToken ct) {
        if (!string.IsNullOrWhiteSpace(name)) {
            var cleaned = new string(name.Where(ch => !char.IsControl(ch)).Take(40).ToArray());
            agentNames[id] = (string.IsNullOrWhiteSpace(cleaned) ? "Agent" : cleaned, Environment.TickCount64);
            foreach (var old in agentNames.OrderBy(pair => pair.Value.At).Take(Math.Max(0, agentNames.Count - 512))) agentNames.TryRemove(old.Key, out _);
        }
        if (!phones.TryGetValue(phone, out var connection)) return;
        try { await Send(connection, new JsonObject { ["type"] = "agent_presence", ["agent_id"] = id,
            ["agent_name"] = AgentName(id), ["connected"] = true }, ct); }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception error) when (error is WebSocketException or IOException or OperationCanceledException or ObjectDisposedException) { }
    }
    public async Task<JsonObject> Invoke(string phone, string tool, JsonObject args, string requestId, bool advanced, CancellationToken ct,
        string agentId = "server", string? agentName = null) {
        if (!phones.TryGetValue(phone, out var connection)) return Error("phone_offline", "手机不在线");
        if (!(connection.Hello["tools"]?.AsArray() ?? new()).Any(t => t?["name"]?.ToString() == tool &&
            (advanced || t?["annotations"]?["destructiveHint"]?.GetValue<bool>() != true))) return Error("tool_unavailable", "工具不可用或高级操作未允许");
        var completion = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
        if (!connection.Pending.TryAdd(requestId, completion)) return Error("device_busy", "请求正在执行");
        var timeout = PhoneToolPolicy.TimeoutSeconds(tool, args);
        try {
            await Send(connection, new JsonObject { ["type"] = "invoke", ["request_id"] = requestId, ["tool"] = tool, ["arguments"] = args.DeepClone(),
                ["allow_advanced"] = advanced, ["deadline"] = DateTimeOffset.UtcNow.AddSeconds(timeout).ToUnixTimeMilliseconds(),
                ["agent_id"] = agentId, ["agent_name"] = agentName ?? AgentName(agentId) }, ct);
            return await completion.Task.WaitAsync(TimeSpan.FromSeconds(timeout + 5), ct);
        } catch (Exception error) when (error is TimeoutException or IOException or WebSocketException or OperationCanceledException) {
            return Error("delivery_unknown", "手机操作回执缺失，请观察核对，不要自动重发");
        } finally { connection.Pending.TryRemove(requestId, out _); }
    }
    public JsonObject CreateBinding(string phone, bool advanced, string agent, string profile) {
        var tools = Tools(phone, advanced); var token = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        var id = Guid.NewGuid().ToString("N");
        lock (diskLock) {
            var bindings = Read("bindings.json");
            bindings[id] = new JsonObject { ["phone_id"] = phone, ["advanced"] = advanced, ["agent"] = agent, ["profile"] = profile, ["token_hash"] = Digest(token), ["session_id"] = "", ["created"] = DateTimeOffset.UtcNow.ToUnixTimeSeconds() };
            Save("bindings.json", bindings);
        }
        return new JsonObject { ["id"] = id, ["token"] = token, ["tools"] = tools };
    }
    public void BindSession(string id, string session) {
        lock (diskLock) { var bindings = Read("bindings.json"); if (bindings[id] is JsonObject row) { row["session_id"] = session; Save("bindings.json", bindings); } }
    }
    public JsonArray ExternalConnections() {
        lock (diskLock) {
            return new JsonArray(Read("bindings.json").Where(pair => pair.Value?["agent"]?.ToString() == "external")
                .Select(pair => (JsonNode)new JsonObject { ["id"] = pair.Key, ["phone_id"] = pair.Value!["phone_id"]?.DeepClone(),
                    ["allow_advanced"] = pair.Value!["advanced"]?.DeepClone(), ["created"] = pair.Value!["created"]?.DeepClone(),
                    ["online"] = phones.ContainsKey(pair.Value!["phone_id"]!.ToString()) }).ToArray());
        }
    }
    public void RemoveBinding(string id) { lock (diskLock) { var rows = Read("bindings.json"); rows.Remove(id); Save("bindings.json", rows); } }
    public void RevokeSession(string agent, string profile, string session) {
        lock (diskLock) { var rows = Read("bindings.json"); foreach (var key in rows.Where(pair => pair.Value?["agent"]?.ToString() == agent && pair.Value?["profile"]?.ToString() == profile && pair.Value?["session_id"]?.ToString() == session).Select(pair => pair.Key).ToArray()) rows.Remove(key); Save("bindings.json", rows); }
    }
    public JsonObject? AuthorizeBinding(string id, string token, string? session = null) {
        lock (diskLock) {
            var row = Read("bindings.json")[id]?.AsObject();
            if (row == null || token.Length < 32 || !CryptographicOperations.FixedTimeEquals(System.Text.Encoding.ASCII.GetBytes(row["token_hash"]!.ToString()), System.Text.Encoding.ASCII.GetBytes(Digest(token)))) return null;
            if (row["session_id"]?.ToString() is not { Length: > 0 } actual || session != null && session != actual) return null;
            return row.DeepClone().AsObject();
        }
    }
    public static JsonObject Error(string code, string message) => new() { ["isError"] = true, ["structuredContent"] = new JsonObject { ["code"] = code, ["message"] = message, ["retryable"] = code != "delivery_unknown" && code == "device_busy" },
        ["content"] = new JsonArray(new JsonObject { ["type"] = "text", ["text"] = code + ": " + message }) };
}
