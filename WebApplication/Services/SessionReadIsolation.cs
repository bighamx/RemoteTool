using System.Diagnostics;
using System.Text.Json;

namespace RemoteTool.WebApi.Services;

/// <summary>Optional per-session metadata must never discard healthy list entries.</summary>
internal static class SessionReadIsolation
{
    public static bool Recoverable(Exception error) => error is JsonException or ArgumentException or
        InvalidOperationException or FormatException or OverflowException or IOException or UnauthorizedAccessException ||
        error.GetType().FullName == "SQLite.SQLiteException";

    public static bool Try(string session, string part, Action read)
    {
        try { read(); return true; }
        catch (Exception error) when (Recoverable(error)) {
            // Do not log the malformed content or model credentials.
            Trace.TraceWarning("Session metadata unavailable: {0}/{1} ({2})", session, part, error.GetType().Name);
            return false;
        }
    }
}
