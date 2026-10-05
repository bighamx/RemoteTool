using System.Net.WebSockets;

internal sealed class FragmentSocket(byte[] bytes) : WebSocket
{
    private int position;
    public WebSocketCloseStatus? ClosedAs;
    public override WebSocketCloseStatus? CloseStatus => ClosedAs;
    public override string CloseStatusDescription => "";
    public override WebSocketState State => ClosedAs == null ? WebSocketState.Open : WebSocketState.CloseSent;
    public override string SubProtocol => "";
    public override void Abort() { }
    public override void Dispose() { }
    public override Task CloseAsync(WebSocketCloseStatus closeStatus, string statusDescription, CancellationToken cancellationToken) => CloseOutputAsync(closeStatus, statusDescription, cancellationToken);
    public override Task CloseOutputAsync(WebSocketCloseStatus closeStatus, string statusDescription, CancellationToken cancellationToken) { ClosedAs = closeStatus; return Task.CompletedTask; }
    public override Task SendAsync(ArraySegment<byte> buffer, WebSocketMessageType messageType, bool endOfMessage, CancellationToken cancellationToken) => Task.CompletedTask;
    public override Task<WebSocketReceiveResult> ReceiveAsync(ArraySegment<byte> buffer, CancellationToken cancellationToken) {
        cancellationToken.ThrowIfCancellationRequested();
        // Fragment in the middle of UTF-8 sequences, not just JSON boundaries.
        var length = Math.Min(Math.Min(buffer.Count, 997), bytes.Length - position);
        Array.Copy(bytes, position, buffer.Array!, buffer.Offset, length); position += length;
        return Task.FromResult(new WebSocketReceiveResult(length, WebSocketMessageType.Text, position == bytes.Length));
    }
}
