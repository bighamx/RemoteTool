using System.Buffers;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

/// <summary>
/// 处理远程控制键鼠 WebSocket 连接，将消息分发到 SystemService。
/// </summary>
public static class InputWebSocketHandler
{
    /// <summary>
    /// 处理已建立的 WebSocket 连接，循环接收并执行键鼠命令直至连接关闭。
    /// </summary>
    public static async Task RunAsync(WebSocket webSocket, SystemService systemService, CancellationToken ct = default)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(4096);
        try
        {
            while (webSocket.State == WebSocketState.Open && !ct.IsCancellationRequested)
            {
                var result = await webSocket.ReceiveAsync(new ArraySegment<byte>(buffer), ct).ConfigureAwait(false);
                if (result.MessageType == WebSocketMessageType.Close)
                {
                    await webSocket.CloseAsync(WebSocketCloseStatus.NormalClosure, null, ct).ConfigureAwait(false);
                    break;
                }

                if (result.MessageType != WebSocketMessageType.Text || !result.EndOfMessage)
                    continue;

                var len = result.Count;
                if (len == 0) continue;

                var json = Encoding.UTF8.GetString(buffer.AsSpan(0, len));
                try
                {
                    Dispatch(systemService, json);
                }
                catch (Exception ex)
                {
                    Console.WriteLine($"[InputWS] Dispatch error: {ex.Message}");
                }
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    private static void Dispatch(SystemService svc, string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        if (!root.TryGetProperty("type", out var typeEl))
            return;

        var type = typeEl.GetString() ?? "";

        switch (type)
        {
            case "click":
                svc.SendMouseClick(GetDouble(root, "x"), GetDouble(root, "y"));
                break;
            case "right-click":
                svc.SendMouseRightClick(GetDouble(root, "x"), GetDouble(root, "y"));
                break;
            case "middle-click":
                svc.SendMouseMiddleClick(GetDouble(root, "x"), GetDouble(root, "y"));
                break;
            case "mouse-down":
                svc.SendMouseDown(GetDouble(root, "x"), GetDouble(root, "y"), GetInt(root, "button", 0));
                break;
            case "mouse-up":
                svc.SendMouseUp(GetDouble(root, "x"), GetDouble(root, "y"), GetInt(root, "button", 0));
                break;
            case "mouse-move":
                svc.SendMouseMove(GetDouble(root, "x"), GetDouble(root, "y"));
                break;
            case "mouse-wheel":
                svc.SendMouseWheel(GetDouble(root, "x"), GetDouble(root, "y"), GetInt(root, "delta", 120));
                break;
            case "keyboard":
                var vk = GetInt(root, "vkCode", 0);
                if (vk >= 0 && vk <= 255)
                    svc.SendKeyboardEvent((byte)vk, root.TryGetProperty("isKeyDown", out var k) && k.GetBoolean());
                break;
            case "keyboard-multi":
                if (root.TryGetProperty("vkCodes", out var codesEl) && codesEl.ValueKind == JsonValueKind.Array)
                {
                    var count = codesEl.GetArrayLength();
                    var arr = new byte[count];
                    for (int i = 0; i < count; i++) arr[i] = (byte)codesEl[i].GetInt32();
                    svc.SendKeyboardEvents(arr, root.TryGetProperty("isKeyDown", out var k2) && k2.GetBoolean());
                }
                break;
            default:
                Console.WriteLine($"[InputWS] Unknown type: {type}");
                break;
        }
    }

    private static double GetDouble(JsonElement root, string name)
    {
        return root.TryGetProperty(name, out var p) ? p.GetDouble() : 0;
    }

    private static int GetInt(JsonElement root, string name, int defaultValue)
    {
        return root.TryGetProperty(name, out var p) ? p.GetInt32() : defaultValue;
    }
}
