using System.ComponentModel;
using System.Runtime.InteropServices;

namespace RemoteTool.WebApi.Services.RemoteControl;

internal static class DisplayPower
{
    // Use a private window on the interactive desktop. Broadcasting to every
    // application can produce repeated transitions and multiply timeout waits.
    public static void ScreenOff()
    {
        if (!OperatingSystem.IsWindows()) throw new PlatformNotSupportedException("当前系统尚不支持息屏");
        var window = CreateWindowEx(0, "STATIC", "RemoteToolDisplay", 0, 0, 0, 0, 0,
            IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero);
        if (window == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        try {
            if (SendMessageTimeout(window, 0x0112, new UIntPtr(0xF170), new IntPtr(2), 0x0002, 3000, out _) == IntPtr.Zero)
                throw new Win32Exception(Marshal.GetLastWin32Error(), "发送息屏请求失败或超时");
        } finally { DestroyWindow(window); }
    }

    [DllImport("user32.dll", EntryPoint = "CreateWindowExW", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr CreateWindowEx(uint exStyle, string className, string title, uint style,
        int x, int y, int width, int height, IntPtr parent, IntPtr menu, IntPtr instance, IntPtr parameter);
    [DllImport("user32.dll", EntryPoint = "SendMessageTimeoutW", SetLastError = true)]
    private static extern IntPtr SendMessageTimeout(IntPtr window, uint message, UIntPtr wParam, IntPtr lParam,
        uint flags, uint timeout, out UIntPtr result);
    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool DestroyWindow(IntPtr window);
}
