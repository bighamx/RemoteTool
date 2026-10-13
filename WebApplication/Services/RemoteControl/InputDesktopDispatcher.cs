using System.Collections.Concurrent;
using System.ComponentModel;
using System.Runtime.InteropServices;
using System.Text;

namespace RemoteTool.WebApi.Services.RemoteControl;

/// <summary>Desktop-bound Win32 work stays on one dedicated native thread, never an async/thread-pool continuation.</summary>
internal static class InputDesktopDispatcher
{
    private sealed record Work(Func<byte[]> Action, TaskCompletionSource<byte[]> Completion, long EnqueuedTicks);
    private static readonly BlockingCollection<Work> Queue = new(64);
    private static string _lastDesktop = "";
    internal static string CurrentDesktopName { get; private set; } = "Default";

    /// <summary>最近一次命令的排队等待 / 桌面附加 / 动作耗时（毫秒），用于诊断。</summary>
    internal static double LastQueueWaitMs { get; private set; }
    internal static double LastAttachMs { get; private set; }
    internal static double LastActionMs { get; private set; }

    static InputDesktopDispatcher()
    {
        var thread = new Thread(Consume) { IsBackground = true, Name = "RemoteTool input desktop" };
        thread.Start();
    }

    public static Task<byte[]> RunAsync(Func<byte[]> action)
    {
        // ⚠️ 不用 RunContinuationsAsynchronously：续接若走线程池唤醒，实测每条命令要多等 ~55ms
        // （本进程里的续接恢复延迟）。这里让续接直接在该桌面线程上内联执行——后续动作只有
        // 「写响应 + 异步入队日志」，都不阻塞，所以内联不会拖住桌面队列，但省掉了那次唤醒等待。
        var completion = new TaskCompletionSource<byte[]>();
        var work = new Work(action, completion, System.Diagnostics.Stopwatch.GetTimestamp());
        if (!Queue.TryAdd(work)) completion.SetException(new InvalidOperationException("Desktop command queue is full"));
        return completion.Task;
    }

    private static double Since(long ticks)
        => (System.Diagnostics.Stopwatch.GetTimestamp() - ticks) * 1000.0 / System.Diagnostics.Stopwatch.Frequency;

    private static void Consume()
    {
        SetThreadDpiAwarenessContext(new IntPtr(-4));
        foreach (var work in Queue.GetConsumingEnumerable())
        {
            IntPtr desktop = IntPtr.Zero, previous = IntPtr.Zero;
            bool attached = false;
            LastQueueWaitMs = Since(work.EnqueuedTicks);
            var attachWatch = System.Diagnostics.Stopwatch.GetTimestamp();
            try
            {
                previous = GetThreadDesktop(GetCurrentThreadId());
                // Preserve the desktop rights this identity already has, including input injection.
                // This opens a handle only; it does not grant rights or modify the desktop ACL.
                desktop = OpenInputDesktop(0, false, 0x02000000); // MAXIMUM_ALLOWED
                if (desktop == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot access the current input desktop");
                var name = new StringBuilder(256);
                GetUserObjectInformation(desktop, 2, name, 512, out _);
                attached = SetThreadDesktop(desktop);
                if (!attached) throw new Win32Exception(Marshal.GetLastWin32Error(), "Cannot attach to the current input desktop");
                CurrentDesktopName = name.ToString();
                Report("Attached to input desktop: " + name);
                LastAttachMs = Since(attachWatch);
                var actionWatch = System.Diagnostics.Stopwatch.GetTimestamp();
                work.Completion.TrySetResult(work.Action());
                LastActionMs = Since(actionWatch);
            }
            catch (Exception ex)
            {
                Report("Input desktop unavailable: " + ex.Message + (ex is Win32Exception native ? $" (Win32 {native.NativeErrorCode})" : ""));
                work.Completion.TrySetException(ex);
            }
            finally
            {
                if (attached && previous != IntPtr.Zero) SetThreadDesktop(previous);
                if (desktop != IntPtr.Zero) CloseDesktop(desktop);
            }
        }
    }

    private static void Report(string state)
    {
        if (state == _lastDesktop) return;
        _lastDesktop = state;
        AgentStartupLogger.Log("InputDesktop", state);
    }

    [DllImport("kernel32.dll")] private static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] private static extern IntPtr SetThreadDpiAwarenessContext(IntPtr context);
    [DllImport("user32.dll")] private static extern IntPtr GetThreadDesktop(uint threadId);
    [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr OpenInputDesktop(uint flags, bool inherit, uint access);
    [DllImport("user32.dll", SetLastError = true)] private static extern bool SetThreadDesktop(IntPtr desktop);
    [DllImport("user32.dll")] private static extern bool CloseDesktop(IntPtr desktop);
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder value, uint length, out uint needed);
}
