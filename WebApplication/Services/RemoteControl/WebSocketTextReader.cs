using System.Net.WebSockets;
using System.Text;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

internal static class WebSocketTextReader
{
    public static async Task<string?> Read(WebSocket socket, byte[] buffer, CancellationToken ct, int limit = 16 * 1024) {
        using var message = new MemoryStream();
        while (true) {
            var part = await socket.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
            if (part.MessageType == WebSocketMessageType.Close) {
                await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, null, ct);
                return null;
            }
            if (part.MessageType != WebSocketMessageType.Text) throw new InvalidDataException("Input requires a text message");
            if (message.Length + part.Count > limit) {
                await socket.CloseOutputAsync(WebSocketCloseStatus.MessageTooBig, "Input message too large", ct);
                return null;
            }
            message.Write(buffer, 0, part.Count);
            if (part.EndOfMessage) return Encoding.UTF8.GetString(message.GetBuffer(), 0, (int)message.Length);
        }
    }
}
