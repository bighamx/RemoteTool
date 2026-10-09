using System.ComponentModel;
using System.Runtime.InteropServices;

namespace RemoteTool.WebApi.Services.RemoteControl;

internal static class NativeDesktopInput
{
    [StructLayout(LayoutKind.Sequential)] private struct Input { public uint Type; public Payload Data; }
    [StructLayout(LayoutKind.Explicit)] private struct Payload { [FieldOffset(0)] public Mouse Mouse; [FieldOffset(0)] public Keyboard Keyboard; }
    [StructLayout(LayoutKind.Sequential)] private struct Mouse { public int X, Y; public uint Data, Flags, Time; public UIntPtr Extra; }
    [StructLayout(LayoutKind.Sequential)] private struct Keyboard { public ushort Key, Scan; public uint Flags, Time; public UIntPtr Extra; }
    [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint count, Input[] inputs, int size);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] private static extern int GetSystemMetrics(int index);

    private static void Submit(Input[] inputs)
    {
        if (inputs.Length == 0) return;
        if (SendInput((uint)inputs.Length, inputs, Marshal.SizeOf<Input>()) != inputs.Length)
            throw new Win32Exception(Marshal.GetLastWin32Error(), "Windows rejected remote input");
    }
    private static Input MouseEvent(uint flags, int x = 0, int y = 0, uint data = 0)
        => new() { Type = 0, Data = new Payload { Mouse = new Mouse { X = x, Y = y, Data = data, Flags = flags } } };

    internal static void ClickAt(double x, double y, int button)
    {
        var px = GetSystemMetrics(76) + (int)(Math.Clamp(x, 0, 1) * (GetSystemMetrics(78) - 1));
        var py = GetSystemMetrics(77) + (int)(Math.Clamp(y, 0, 1) * (GetSystemMetrics(79) - 1));
        if (!SetCursorPos(px, py)) throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot move remote pointer");
        Execute("pointer-click", "", 0, 0, button);
    }
    internal static void Execute(string kind, string text, int dx, int dy, int button)
    {
        switch (kind)
        {
            case "mouse-relative":
                if (dx != 0 || dy != 0) Submit(new[] { MouseEvent(1, Math.Clamp(dx, -4096, 4096), Math.Clamp(dy, -4096, 4096)) });
                break;
            case "pointer-click":
                uint down = button == 2 ? 8u : button == 1 ? 32u : 2u;
                Submit(new[] { MouseEvent(down), MouseEvent(down << 1) }); break;
            case "pointer-wheel": Submit(new[] { MouseEvent(0x800, data: unchecked((uint)dy)) }); break;
            case "text":
                if (text.Length > 2048) throw new ArgumentException("Text is too long");
                var keys = new List<Input>();
                foreach (var c in text)
                {
                    keys.Add(new Input { Type = 1, Data = new Payload { Keyboard = new Keyboard { Scan = c, Flags = 4 } } });
                    keys.Add(new Input { Type = 1, Data = new Payload { Keyboard = new Keyboard { Scan = c, Flags = 6 } } });
                }
                Submit(keys.ToArray()); break;
            default: throw new ArgumentException("Unknown input action");
        }
    }
}
