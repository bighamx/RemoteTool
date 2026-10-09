using System.Buffers;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.RemoteControl;

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
                var json = await WebSocketTextReader.Read(webSocket, buffer, ct).ConfigureAwait(false);
                if (json == null) break;
                if (json.Length == 0) continue;
                try
                {
                    await DispatchAsync(systemService, json);
                    using var request = JsonDocument.Parse(json);
                    if (request.RootElement.TryGetProperty("ack", out var ack) && ack.ValueKind == JsonValueKind.True)
                        await webSocket.SendAsync(new ArraySegment<byte>(Encoding.UTF8.GetBytes("{\"type\":\"input-ack\",\"ok\":true}")), WebSocketMessageType.Text, true, ct);
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

    private static async Task DispatchAsync(SystemService svc, string json)
    {
        using var doc = JsonDocument.Parse(json);
        var root = doc.RootElement;
        if (!root.TryGetProperty("type", out var typeEl))
            return;

        var type = typeEl.GetString() ?? "";
        // A connection check must not synthesize mouse input on the desktop.
        if (type == "mouse-relative" && GetInt(root, "dx", 0) == 0 && GetInt(root, "dy", 0) == 0) return;

        if (type is "text" or "mouse-relative" or "pointer-click" or "pointer-wheel")
        {
            var text = root.TryGetProperty("text", out var t) ? t.GetString() ?? "" : "";
            if (text.Length > 2048) throw new ArgumentException("Text is too long");
            if (InteractiveProcessLauncher.IsRunningInSession0)
                await DesktopAgent.SendNativeInputAsync(type, text, GetInt(root, "dx", 0), GetInt(root, "dy", 0), GetInt(root, "button", 0));
            else await InputDesktopDispatcher.RunAsync(() => { NativeDesktopInput.Execute(type, text, GetInt(root, "dx", 0), GetInt(root, "dy", 0), GetInt(root, "button", 0)); return Array.Empty<byte>(); });
            return;
        }

        // Await each command so pointer movement, press and release cannot overtake one another.
        if (InteractiveProcessLauncher.IsRunningInSession0)
        {
            double x = GetDouble(root, "x"), y = GetDouble(root, "y");
            switch (type)
            {
                case "click": await DesktopAgent.SendMouseClickAsync(x,y); break;
                case "right-click": await DesktopAgent.SendMouseRightClickAsync(x,y); break;
                case "middle-click": await DesktopAgent.SendMouseMiddleClickAsync(x,y); break;
                case "mouse-move": await DesktopAgent.SendMouseMoveAsync(x,y); break;
                case "mouse-down": await DesktopAgent.SendMouseDownAsync(x,y,GetInt(root,"button",0)); break;
                case "mouse-up": await DesktopAgent.SendMouseUpAsync(x,y,GetInt(root,"button",0)); break;
                case "mouse-wheel": await DesktopAgent.SendMouseWheelAsync(x,y,GetInt(root,"delta",120)); break;
                case "keyboard": await DesktopAgent.SendKeyboardEventAsync((byte)Math.Clamp(GetInt(root,"vkCode",0),0,255),root.TryGetProperty("isKeyDown",out var k0)&&k0.GetBoolean()); break;
                case "keyboard-multi": await DesktopAgent.SendKeyboardEventsAsync(root.GetProperty("vkCodes").EnumerateArray().Select(v=>(byte)Math.Clamp(v.GetInt32(),0,255)).ToArray(),root.TryGetProperty("isKeyDown",out var k1)&&k1.GetBoolean()); break;
            }
            return;
        }

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
