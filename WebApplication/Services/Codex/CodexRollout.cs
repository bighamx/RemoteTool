using System.Text.Json;
using System.Text.Json.Nodes;
using System.Runtime.InteropServices;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexRollout
{
    public static string CompactResult(string home, string path, long offset) {
        if (!Path.GetFullPath(path).StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException("Invalid rollout path");
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        stream.Seek(offset, SeekOrigin.Begin);
        using var reader = new StreamReader(stream);
        while (reader.ReadLine() is { } line) {
            try {
                var record = JsonNode.Parse(line);
                if (record?["type"]?.ToString() == "compacted") return "completed";
                if (record?["type"]?.ToString() == "event_msg") {
                    var kind = record["payload"]?["type"]?.ToString();
                    if (kind == "turn_aborted") return "interrupted";
                    if (kind == "error") return "failed";
                }
            } catch (JsonException) { }
        }
        return "";
    }
    public static bool IsRunning(string home, string path) {
        if (!WriterLocked(home, path)) return false;
        var snapshot = CodexRolloutSnapshot.Read(home, path);
        if (!snapshot.B("running")) return false;
        // STALE 自愈：客户端被杀时 task_complete 没写入，running 会永久残留。
        // 正常运行中的 turn 会持续写 rollout 事件；超过 10 分钟无任何事件视为已结束。
        if (snapshot["last_event_at"] is not null && DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() - snapshot.L("last_event_at") > TimeSpan.FromMinutes(10).TotalMilliseconds) return false;
        return true;
    }
    public static bool WriterLocked(string home, string path) {
        if (string.IsNullOrWhiteSpace(path) || !(OperatingSystem.IsWindows() || OperatingSystem.IsMacOS())) return false;
        if (!Path.GetFullPath(path).StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) return false;
        var locks = Path.Combine(home, "thread-writer-locks");
        var id = System.Text.RegularExpressions.Regex.Match(Path.GetFileName(path), "[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}").Value;
        var nativeLock = Directory.Exists(locks) && id.Length > 0;
        var target = nativeLock ? Path.Combine(locks, id + ".lock") : path;
        if (!File.Exists(target)) return false;
        if (OperatingSystem.IsMacOS()) {
            if (!nativeLock) return false;
            // FileStream itself takes a shared flock on Unix and would hide a busy writer.
            var fd = open(target, 0);
            if (fd < 0) return false;
            try {
                if (flock(fd, 2 | 4) == 0) { flock(fd, 8); return false; }
                return Marshal.GetLastPInvokeError() == 35; // Darwin EWOULDBLOCK.
            } finally { close(fd); }
        }
        try { using var probe = new FileStream(target, FileMode.Open, FileAccess.Read, nativeLock ? FileShare.None : FileShare.Read); return false; }
        catch (IOException error) when ((error.HResult & 0xffff) is 32 or 33) { return true; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { return false; }
    }
    /// <summary>
    /// codex 恢复会话时会比对 rollout session_meta 里的 model_provider 与当前 Provider,
    /// 不一致直接拒绝（跨 Provider 切换会话模型就是被这条挡住）。切换前把会话归属改写到
    /// 目标 Provider, 保留原文件备份; 只改首行元数据, 历史内容原样不动。
    /// </summary>
    public static bool RehomeProvider(string home, string path, string provider) {
        if (provider.Length == 0) return false;
        var full = Path.GetFullPath(path);
        if (!full.StartsWith(Path.GetFullPath(home) + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)) throw new IOException("Invalid rollout path");
        if (!File.Exists(full)) throw new FileNotFoundException("rollout 文件不存在", full);
        byte[] bytes;
        using (var stream = new FileStream(full, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete)) {
            bytes = new byte[stream.Length]; stream.ReadExactly(bytes);
        }
        var newline = Array.IndexOf(bytes, (byte)'\n');
        var head = System.Text.Encoding.UTF8.GetString(bytes, 0, newline < 0 ? bytes.Length : newline);
        if (JsonNode.Parse(head) is not JsonObject record || record["payload"] is not JsonObject payload) return false;
        if (payload.S("model_provider") == provider) return false;
        payload["model_provider"] = provider;
        var rebuilt = System.Text.Encoding.UTF8.GetBytes(record.ToJsonString());
        var tail = newline < 0 ? Array.Empty<byte>() : bytes[newline..];
        var temporary = full + ".rehome.tmp";
        try {
            using (var stream = new FileStream(temporary, FileMode.Create, FileAccess.Write, FileShare.None)) { stream.Write(rebuilt); stream.Write(tail); }
            File.Copy(full, full + ".provider-backup", true);
            File.Move(temporary, full, true);
            return true;
        } finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    public static string LastModel(string home, string path) => LastSettings(home, path).S("model");
    public static JsonObject LastSettings(string home, string path) => CodexRolloutSnapshot.Read(home, path)["settings"]!.AsObject();
    [DllImport("libc", SetLastError = true)] private static extern int flock(int fd, int operation);
    [DllImport("libc", SetLastError = true)] private static extern int open(string path, int flags);
    [DllImport("libc")] private static extern int close(int fd);
}
