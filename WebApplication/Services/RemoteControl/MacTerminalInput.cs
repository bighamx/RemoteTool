using System.Text;
using System.Text.Json;

namespace RemoteTool.WebApi.Services.RemoteControl;

internal static class MacTerminalInput
{
    public static (byte Opcode, byte[] Payload)? Encode(string message) {
        JsonDocument parsed = null;
        if (message.TrimStart().StartsWith('{')) {
            try { parsed = JsonDocument.Parse(message); }
            catch (JsonException) { } // A shell block such as { echo hello; } is ordinary terminal input.
        }
        using (parsed) {
            if (parsed?.RootElement is { ValueKind: JsonValueKind.Object } root && root.TryGetProperty("type", out var type) && type.ValueKind == JsonValueKind.String) {
                switch (type.GetString()) {
                    case "resize":
                        var cols = Math.Clamp(root.GetProperty("cols").GetInt32(), 20, 500);
                        var rows = Math.Clamp(root.GetProperty("rows").GetInt32(), 5, 200);
                        return (2, [(byte)cols, (byte)(cols >> 8), (byte)rows, (byte)(rows >> 8)]);
                    case "input": return (1, Encoding.UTF8.GetBytes(root.GetProperty("data").GetString() ?? ""));
                    default: return null;
                }
            }
            return (1, Encoding.UTF8.GetBytes(message + "\r"));
        }
    }
}
