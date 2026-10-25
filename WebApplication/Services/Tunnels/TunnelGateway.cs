using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace RemoteTool.WebApi.Services.Tunnels;

public sealed class TunnelGateway
{
    private readonly ConcurrentDictionary<string, TunnelSession> sessions = new();
    private readonly ConcurrentDictionary<string, string> stoppedRuns = new();
    internal static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { PropertyNamingPolicy = JsonNamingPolicy.SnakeCaseLower };
    public object[] Status() => sessions.Values.Select(session => session.Status()).ToArray();
    public async Task<bool> Stop(string id) {
        if (!sessions.TryGetValue(id, out var session)) return false;
        stoppedRuns[id] = session.RunId;
        if (stoppedRuns.Count > 512) foreach (var old in stoppedRuns.Keys.Where(key => !sessions.ContainsKey(key)).Take(stoppedRuns.Count - 512)) stoppedRuns.TryRemove(old, out _);
        try { await session.Stop(); }
        catch (Exception error) when (error is IOException or WebSocketException or OperationCanceledException or ObjectDisposedException) { }
        return true;
    }
    public async Task Attach(WebSocket socket, CancellationToken cancellation, Func<bool> authorized) {
        TunnelSession session = null;
        using var lifetime = CancellationTokenSource.CreateLinkedTokenSource(cancellation);
        try {
            using var helloTimeout = CancellationTokenSource.CreateLinkedTokenSource(lifetime.Token);
            helloTimeout.CancelAfter(TimeSpan.FromSeconds(15));
            var hello = await Read(socket, helloTimeout.Token);
            if (hello.Type != WebSocketMessageType.Text) throw new InvalidDataException("需要隧道配置");
            using var doc = JsonDocument.Parse(hello.Bytes);
            var root = doc.RootElement;
            if (root.GetProperty("type").GetString() != "configure" || root.GetProperty("version").GetInt32() != 1)
                throw new InvalidDataException("不支持的隧道版本");
            var client = root.GetProperty("client_id").GetString() ?? "";
            var runId = root.GetProperty("run_id").GetString() ?? "";
            if (!System.Text.RegularExpressions.Regex.IsMatch(client, "^[a-zA-Z0-9_-]{8,80}$")) throw new InvalidDataException("无效的客户端标识");
            if (!Guid.TryParse(runId, out _)) throw new InvalidDataException("无效的隧道运行标识");
            if (stoppedRuns.TryGetValue(client, out var stopped) && stopped == runId) {
                await socket.SendAsync(JsonSerializer.SerializeToUtf8Bytes(new { type = "stopped" }), WebSocketMessageType.Text, true, helloTimeout.Token);
                return;
            }
            var rules = root.GetProperty("rules").Deserialize<TunnelRule[]>(Json) ?? [];
            if (rules.Length is < 1 or > 32 || rules.Any(rule => rule == null) || rules.Select(rule => rule.Id).Distinct().Count() != rules.Length)
                throw new InvalidDataException("每个连接支持 1–32 条转发规则");
            foreach (var rule in rules) rule.Validate();
            var name = root.TryGetProperty("name", out var n) ? n.GetString() ?? "手机" : "手机";
            if (name.Length > 80) name = name[..80];
            session = new TunnelSession(client, runId, name, socket, rules, lifetime);
            if (sessions.TryGetValue(client, out var old)) old.Dispose();
            sessions[client] = session;
            await session.Run(authorized);
        } catch (Exception error) when (error is IOException or System.Net.Sockets.SocketException or WebSocketException or OperationCanceledException or
            ObjectDisposedException or JsonException or KeyNotFoundException or InvalidOperationException or FormatException or ArgumentException or OverflowException) {
            if (!cancellation.IsCancellationRequested && socket.State == WebSocketState.Open) {
                try {
                    var message = JsonSerializer.SerializeToUtf8Bytes(new { type = "error", message = error.Message });
                    using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(2));
                    if (session != null) await session.SendText(message, timeout.Token);
                    else await socket.SendAsync(message, WebSocketMessageType.Text, true, timeout.Token);
                } catch { }
            }
        } finally {
            if (session != null) {
                ((ICollection<KeyValuePair<string, TunnelSession>>)sessions).Remove(new(session.Id, session));
                session.Dispose();
            }
            socket.Abort();
        }
    }
    internal static async Task<(WebSocketMessageType Type, byte[] Bytes)> Read(WebSocket socket, CancellationToken ct) {
        using var result = new MemoryStream(); var buffer = new byte[16384]; WebSocketMessageType? type = null;
        while (true) {
            var part = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
            if (part.MessageType == WebSocketMessageType.Close) throw new IOException("隧道已断开");
            type ??= part.MessageType;
            if (part.MessageType != type || result.Length + part.Count > TunnelFrame.MaxFrame) throw new InvalidDataException("隧道消息过大");
            result.Write(buffer, 0, part.Count);
            if (part.EndOfMessage) return (type.Value, result.ToArray());
        }
    }
}
