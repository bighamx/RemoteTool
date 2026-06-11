using System.Diagnostics;
using System.Text;

namespace ChuckieHelper.WebApi.Services.RemoteControl;

/// <summary>
/// 远程桌面代理启动诊断日志（控制台 + 文件）。
/// </summary>
internal static class AgentStartupLogger
{
    private static readonly object SyncLock = new();
    private static string _activeLogPath = ResolvePreferredPath();

    public static string LogFilePath => _activeLogPath;

    public static void Log(string source, string message)
    {
        var line = BuildLine(source, message, null);
        Console.WriteLine($"[{source}] {message}");
        AppendLine(line);
    }

    public static void LogException(string source, string message, Exception ex)
    {
        var line = BuildLine(source, message, ex);
        Console.WriteLine($"[{source}] {message}: {ex.Message}");
        AppendLine(line);
    }

    private static string BuildLine(string source, string message, Exception ex)
    {
        var sb = new StringBuilder();
        sb.Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff"));
        sb.Append(" [");
        sb.Append(source);
        sb.Append("] ");
        sb.Append("pid=");
        sb.Append(Environment.ProcessId);
        sb.Append(" session=");
        sb.Append(GetSessionIdSafe());
        sb.Append(" user=");
        sb.Append(Environment.UserDomainName);
        sb.Append('\\');
        sb.Append(Environment.UserName);
        sb.Append(" | ");
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

    private static void AppendLine(string line)
    {
        lock (SyncLock)
        {
            if (TryAppend(_activeLogPath, line))
                return;

            var fallback = GetFallbackPath();
            if (string.Equals(_activeLogPath, fallback, StringComparison.OrdinalIgnoreCase))
                return;

            _activeLogPath = fallback;
            TryAppend(_activeLogPath, $"{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff} [AgentStartupLogger] 主日志路径写入失败，切换到: {_activeLogPath}");
            TryAppend(_activeLogPath, line);
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
            return Path.Combine(programData, "ChuckieHelper", "logs", "desktop-agent-startup.log");

        return GetFallbackPath();
    }

    private static string GetFallbackPath()
        => Path.Combine(Path.GetTempPath(), "ChuckieHelper", "desktop-agent-startup.log");
}
