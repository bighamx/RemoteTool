using System.Net.WebSockets;
using System.Text;
using System.Text.Json.Nodes;
using System.Threading.Channels;

// Deterministic test transport; never attaches to a production listener or binding.
sealed class FaultingPhoneSocket(JsonObject hello, bool failInvokeSend) : WebSocket {
    private readonly Channel<byte[]> incoming = Channel.CreateUnbounded<byte[]>();
    private volatile WebSocketState state = WebSocketState.Open;
    private byte[]? initial = Encoding.UTF8.GetBytes(hello.ToJsonString());
    public TaskCompletionSource Ready { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public TaskCompletionSource InvokeReceived { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
    public override WebSocketCloseStatus? CloseStatus => null;
    public override string? CloseStatusDescription => null;
    public override WebSocketState State => state;
    public override string? SubProtocol => null;
    public override void Abort() { state = WebSocketState.Aborted; incoming.Writer.TryComplete(); }
    public override void Dispose() => Abort();
    public override Task CloseAsync(WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken) { Abort(); return Task.CompletedTask; }
    public override Task CloseOutputAsync(WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken) { Abort(); return Task.CompletedTask; }
    public override async Task<WebSocketReceiveResult> ReceiveAsync(ArraySegment<byte> buffer, CancellationToken cancellationToken) {
        var bytes = Interlocked.Exchange(ref initial, null);
        if (bytes == null) {
            try { bytes = await incoming.Reader.ReadAsync(cancellationToken); }
            catch (ChannelClosedException) { return new(0, WebSocketMessageType.Close, true); }
        }
        if (bytes.Length > buffer.Count) throw new InvalidOperationException("Mock frame exceeds supplied buffer");
        bytes.AsSpan().CopyTo(buffer.AsSpan());
        return new(bytes.Length, WebSocketMessageType.Text, true);
    }
    public override Task SendAsync(ArraySegment<byte> buffer, WebSocketMessageType messageType, bool endOfMessage, CancellationToken cancellationToken) {
        cancellationToken.ThrowIfCancellationRequested();
        var message = JsonNode.Parse(Encoding.UTF8.GetString(buffer.AsSpan()))!;
        if (message["type"]?.ToString() == "ready") Ready.TrySetResult();
        if (message["type"]?.ToString() == "invoke") {
            InvokeReceived.TrySetResult();
            if (failInvokeSend) throw new WebSocketException("Injected failure inside SendAsync");
        }
        return Task.CompletedTask;
    }
}
