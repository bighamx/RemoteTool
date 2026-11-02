using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace RemoteTool.WebApi.Services.Updates;

public sealed record UpdaterProcessIdentity(int Pid, long StartCookie);

/// <summary>Inherit the server's primary token/environment, without inheriting its lifetime.</summary>
public static class UpdaterProcess
{
    public static long StartCookie(Process process) {
        if (!OperatingSystem.IsLinux()) return process.StartTime.ToUniversalTime().Ticks;
        var stat=File.ReadAllText($"/proc/{process.Id}/stat");
        return long.Parse(stat[(stat.LastIndexOf(')')+2)..].Split(' ',StringSplitOptions.RemoveEmptyEntries)[19],System.Globalization.CultureInfo.InvariantCulture);
    }
    public static UpdaterProcessIdentity Start(string dotnet, string assembly, string configuration) =>
        StartCommand(dotnet, [assembly, "--server-updater", configuration], Path.GetDirectoryName(assembly)!);

    public static UpdaterProcessIdentity StartCommand(string executable, string[] arguments, string directory) {
        if (OperatingSystem.IsWindows()) return WindowsStart(executable, arguments, directory);
        if (!OperatingSystem.IsLinux()) throw new PlatformNotSupportedException("更新进程暂支持 Windows / Linux");
        // Linux systemd installations require KillMode=process. setsid also separates
        // terminal signals; it does not pretend to escape a systemd control group.
        var info = new ProcessStartInfo("/usr/bin/setsid") { UseShellExecute = false, CreateNoWindow = true, WorkingDirectory = directory };
        info.ArgumentList.Add(executable); foreach (var value in arguments) info.ArgumentList.Add(value);
        using var process = Process.Start(info) ?? throw new IOException("无法启动更新进程");
        return new(process.Id, StartCookie(process));
    }
    private static string Quote(string value) {
        var result = new StringBuilder("\""); var slashes = 0;
        foreach (var character in value) {
            if (character == '\\') { slashes++; continue; }
            result.Append('\\', character == '"' ? slashes * 2 + 1 : slashes); result.Append(character); slashes = 0;
        }
        return result.Append('\\', slashes * 2).Append('"').ToString();
    }
    private static UpdaterProcessIdentity WindowsStart(string executable, string[] arguments, string directory) {
        if (!IsProcessInJob(GetCurrentProcess(), IntPtr.Zero, out var inJob)) throw new Win32Exception(Marshal.GetLastWin32Error());
        var info = new StartupInfo { Size = Marshal.SizeOf<StartupInfo>() };
        // CREATE_SUSPENDED | CREATE_NO_WINDOW; explicitly leave the IIS job if present.
        var flags = 0x00000004u | 0x08000000u | (inJob ? 0x01000000u : 0u);
        if (!CreateProcess(executable, new StringBuilder(string.Join(" ", new[] { executable }.Concat(arguments).Select(Quote))),
            IntPtr.Zero, IntPtr.Zero, false, flags, IntPtr.Zero, directory, ref info, out var created))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "无法以服务端身份独立启动更新进程；网站未停止");
        try {
            if (inJob && CurrentJobContains(created.Pid))
                throw new IOException("更新进程仍受 IIS Job 管理，已取消启动；网站未停止");
            using var child = Process.GetProcessById((int)created.Pid);
            var identity = new UpdaterProcessIdentity(child.Id, child.StartTime.ToUniversalTime().Ticks);
            if (ResumeThread(created.Thread) == uint.MaxValue) throw new Win32Exception(Marshal.GetLastWin32Error());
            return identity;
        } catch { TerminateProcess(created.Process, 1); throw; }
        finally { CloseHandle(created.Thread); CloseHandle(created.Process); }
    }
    private static bool CurrentJobContains(uint pid) {
        // Check the hosting job specifically; an unrelated outer supervisor may
        // legitimately own both the server and its detached update process.
        for (var size = 4096; size <= 1_048_576; size *= 2) {
            var data = Marshal.AllocHGlobal(size);
            try {
                if (!QueryInformationJobObject(IntPtr.Zero, 3, data, (uint)size, out _)) {
                    var error = Marshal.GetLastWin32Error(); if (error == 234) continue;
                    throw new Win32Exception(error);
                }
                var count = Marshal.ReadInt32(data,4);
                for (var index=0; index<count; index++) if ((ulong)Marshal.ReadIntPtr(data,8+index*IntPtr.Size).ToInt64()==pid) return true;
                return false;
            } finally { Marshal.FreeHGlobal(data); }
        }
        throw new IOException("无法核对 IIS Job 进程范围");
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct StartupInfo {
        public int Size; public string? Reserved, Desktop, Title;
        public uint X, Y, XSize, YSize, XChars, YChars, Fill, Flags; public short Show, ReservedBytes;
        public IntPtr ReservedPointer, Input, Output, Error;
    }
    [StructLayout(LayoutKind.Sequential)] private struct ProcessInfo { public IntPtr Process, Thread; public uint Pid, Tid; }
    [DllImport("kernel32.dll")] private static extern IntPtr GetCurrentProcess();
    [DllImport("kernel32.dll", SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool IsProcessInJob(IntPtr process, IntPtr job, out bool result);
    [DllImport("kernel32.dll", SetLastError=true)] private static extern bool QueryInformationJobObject(IntPtr job,int type,IntPtr data,uint size,out uint needed);
    [DllImport("kernel32.dll", EntryPoint = "CreateProcessW", CharSet = CharSet.Unicode, SetLastError = true)] [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool CreateProcess(string application, StringBuilder command, IntPtr processAttributes, IntPtr threadAttributes,
        bool inheritHandles, uint flags, IntPtr environment, string directory, ref StartupInfo startup, out ProcessInfo process);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern uint ResumeThread(IntPtr thread);
    [DllImport("kernel32.dll")] private static extern bool TerminateProcess(IntPtr process, uint code);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
}
