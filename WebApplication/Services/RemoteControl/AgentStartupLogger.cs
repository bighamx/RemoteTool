using System.Collections.Concurrent;
using System.Diagnostics;
using System.Text;

namespace RemoteTool.WebApi.Services.RemoteControl;

/// <summary>
/// 远程桌面代理启动诊断日志（控制台 + 文件）。
/// 文件写入是异步的：见 AppendLine 的说明。
/// </summary>
internal static class AgentStartupLogger
{
    private static readonly object SyncLock = new();
    private static string _activeLogPath = ResolvePreferredPath();

    /// <summary>待落盘日志队列（有界；满了就丢弃，绝不阻塞调用方）。存 (文件行, 控制台文本)。</summary>
    private static readonly BlockingCollection<(string Line, string ConsoleText)> _logQueue = new(new ConcurrentQueue<(string, string)>(), 5000);

    static AgentStartupLogger()
    {
        var writer = new Thread(LogWriterLoop) { IsBackground = true, Name = "AgentLogWriter" };
        writer.Start();
    }

    public static string LogFilePath => _activeLogPath;

    /// <summary>
    /// 固定前缀（pid/session/user）——只解析一次。
    /// ⚠️⚠️ 本机实测 `Process.GetCurrentProcess().SessionId` 单次要 **~95ms**，
    /// 而日志是每条桌面命令都会走的路径：每行都实时查询会把命令从 1ms 拖到 100-300ms
    /// （曾表现为"远程控制鼠标键盘延迟巨大"，实测 keyboard 312ms → 关掉日志 2ms）。
    /// 进程身份整生命周期不变，缓存即可。
    /// </summary>
    private static readonly string ProcessPrefix = BuildProcessPrefix();

    private static string BuildProcessPrefix()
    {
        var sb = new StringBuilder(96);
        sb.Append("pid=").Append(Environment.ProcessId);
        sb.Append(" session=").Append(GetSessionIdSafe());
        sb.Append(" user=").Append(Environment.UserDomainName);
        sb.Append('\\').Append(Environment.UserName);
        sb.Append(" | ");
        return sb.ToString();
    }

    public static void Log(string source, string message)
    {
        var line = BuildLine(source, message, null);
        Enqueue(line, $"[{source}] {message}");
    }

    public static void LogException(string source, string message, Exception ex)
    {
        var line = BuildLine(source, message, ex);
        Enqueue(line, $"[{source}] {message}: {ex.Message}");
    }

    private static string BuildLine(string source, string message, Exception ex)
    {
        var sb = new StringBuilder(256);
        sb.Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff"));
        sb.Append(" [").Append(source).Append("] ");
        sb.Append(ProcessPrefix);   // 缓存的前缀，不再实时查询会话/用户
        sb.Append(message);

        if (ex != null)
        {
            sb.Append(" | ex=");
            sb.Append(ex.GetType().Name);
            sb.Append(": ");
            sb.Append(ex.Message);
            if (!string.IsNullOrWhiteSpace(ex.StackTrace))
            {
                sb.AppendLine();
                sb.Append(ex.StackTrace);
            }
        }

        return sb.ToString();
    }

    private static int GetSessionIdSafe()
    {
        try
        {
            return Process.GetCurrentProcess().SessionId;
        }
        catch
        {
            return -1;
        }
    }

    /// <summary>
    /// 入队一行日志。⚠️ 绝不能同步输出：实测本进程里同步 Console.WriteLine 单次要 ~53ms，
    /// 文件 AppendAllText 甚至 50-300ms（两个进程同时写 + 疑似安全软件扫描）。
    /// 同步写会把每条桌面命令从 1ms 拖到 50ms+，所以两者都交给后台线程。
    /// </summary>
    private static void Enqueue(string line, string consoleText)
    {
        if (_logQueue.IsAddingCompleted) return;
        try { _logQueue.TryAdd((line, consoleText)); }
        catch (InvalidOperationException) { /* 队列已关闭 */ }
    }

    /// <summary>后台落盘线程：合并已到达的日志行，控制台与文件都在这里输出。</summary>
    private static void LogWriterLoop()
    {
        var batch = new StringBuilder();
        foreach (var (line, consoleText) in _logQueue.GetConsumingEnumerable())
        {
            Console.WriteLine(consoleText);
            batch.Clear().Append(line);
            // 合并 20ms 窗口内的其它日志行，降低写入次数
            while (batch.Length < 64 * 1024 && _logQueue.TryTake(out var more, 20))
                batch.Append('\n').Append(more.Line);
            WriteDirect(batch.ToString());
        }
    }

    private static void WriteDirect(string block)
    {
        lock (SyncLock)
        {
            if (TryAppend(_activeLogPath, block))
                return;

            var fallback = GetFallbackPath();
            if (string.Equals(_activeLogPath, fallback, StringComparison.OrdinalIgnoreCase))
                return;

            _activeLogPath = fallback;
            TryAppend(_activeLogPath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [AgentStartupLogger] 主日志路径写入失败，切换到: {_activeLogPath}");
            TryAppend(_activeLogPath, block);
        }
    }

    private static bool TryAppend(string path, string line)
    {
        try
        {
            var dir = Path.GetDirectoryName(path);
            if (!string.IsNullOrWhiteSpace(dir))
                Directory.CreateDirectory(dir);

            File.AppendAllText(path, line + Environment.NewLine, Encoding.UTF8);
            return true;
        }
        catch
        {
            return false;
        }
    }

    private static string ResolvePreferredPath()
    {
        var fromEnv = Environment.GetEnvironmentVariable("CHUCKIEHELPER_AGENT_LOG");
        if (!string.IsNullOrWhiteSpace(fromEnv))
            return fromEnv;

        var programData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        if (!string.IsNullOrWhiteSpace(programData))
            return RemoteToolPaths.DesktopAgentLog;

        return GetFallbackPath();
    }

    private static string GetFallbackPath()
        => Path.Combine(RemoteToolPaths.TempData, "desktop-agent-startup.log");
}
