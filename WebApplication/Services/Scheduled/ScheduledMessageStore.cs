using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace RemoteTool.WebApi.Services.Scheduled;

/// <summary>定时消息的状态机：pending → sending → sent | failed；pending → cancelled。</summary>
public static class ScheduledMessageStatus
{
    public const string Pending = "pending";
    public const string Sending = "sending";
    public const string Sent = "sent";
    public const string Failed = "failed";
    public const string Cancelled = "cancelled";
}

public sealed record ScheduledMessage(
    string Id, string Agent, string SessionId, string Text, long SendAt, string TimeZone,
    string Status, int Attempts, string LastError, long CreatedAt, long? SentAt, string RunId,
    string ClientKey);

/// <summary>
/// 定时消息持久化：ProgramData 下的 JSON，原子写入，进程内单实例。
/// 与 OidcProviderStore / CodexSessionMoves 同一套落盘模式（临时文件 + 覆盖改名）。
/// </summary>
public sealed class ScheduledMessageStore
{
    public const int MaxTextLength = 16_000;
    public const int MaxPendingPerSession = 20;
    public const int MaxPendingTotal = 200;
    public const long MaxLeadSeconds = 90L * 24 * 3600;
    /** 错过时间超过这个窗口就不再补发（用户约定：30 分钟）。 */
    public const long MissedWindowSeconds = 30 * 60;
    private const long TerminalRetentionSeconds = 7L * 24 * 3600;

    private readonly object _gate = new();
    private readonly string _path;
    private List<ScheduledMessage> _rows;

    public ScheduledMessageStore(IConfiguration configuration)
        : this(Path.Combine(RemoteToolPaths.ProgramData, "scheduled-messages.json")) { }

    internal ScheduledMessageStore(string path)
    {
        _path = path;
        IdentityDataDirectory.Ensure(Path.GetDirectoryName(path)!);
        _rows = File.Exists(path)
            ? JsonSerializer.Deserialize<List<ScheduledMessage>>(File.ReadAllText(path)) ?? []
            : [];
    }

    public IReadOnlyList<ScheduledMessage> List(string sessionId = null, string status = null)
    {
        lock (_gate)
            return _rows
                .Where(row => sessionId == null || row.SessionId == sessionId)
                .Where(row => status == null || row.Status == status)
                .OrderBy(row => row.SendAt)
                .ToArray();
    }

    public ScheduledMessage Find(string id)
    {
        lock (_gate) return _rows.FirstOrDefault(row => row.Id == id);
    }

    public ScheduledMessage Add(string sessionId, string text, long sendAt, string timeZone, string clientKey, long now)
    {
        sessionId = (sessionId ?? "").Trim();
        text = text ?? "";
        timeZone = (timeZone ?? "").Trim();
        clientKey = (clientKey ?? "").Trim();
        if (!Regex.IsMatch(sessionId, "^[a-zA-Z0-9_-]{1,160}$")) throw new ArgumentException("无效会话标识");
        if (text.Trim().Length == 0) throw new ArgumentException("定时消息内容不能为空");
        if (text.Length > MaxTextLength) throw new ArgumentException($"定时消息最多 {MaxTextLength} 个字符");
        if (sendAt <= 0 || sendAt > now + MaxLeadSeconds) throw new ArgumentException("发送时间不在允许范围内（最多提前 90 天）");
        if (sendAt < now - MissedWindowSeconds) throw new ArgumentException("发送时间已过去超过 30 分钟");
        if (timeZone.Length > 64) throw new ArgumentException("时区标识过长");
        if (clientKey.Length > 120) throw new ArgumentException("clientKey 过长");

        lock (_gate)
        {
            // 同一 clientKey 重复提交返回同一条记录，避免离线重试产生重复定时消息。
            if (clientKey.Length > 0 && _rows.FirstOrDefault(row => row.ClientKey == clientKey) is { } existing) return existing;
            if (_rows.Count(row => row.Status == ScheduledMessageStatus.Pending && row.SessionId == sessionId) >= MaxPendingPerSession)
                throw new ArgumentException($"该会话最多同时有 {MaxPendingPerSession} 条待发送的定时消息");
            if (_rows.Count(row => row.Status == ScheduledMessageStatus.Pending) >= MaxPendingTotal)
                throw new ArgumentException($"待发送的定时消息总数已达上限 {MaxPendingTotal}");
            var row = new ScheduledMessage(Guid.NewGuid().ToString("N")[..20], "codex", sessionId, text, sendAt,
                timeZone, ScheduledMessageStatus.Pending, 0, "", now, null, "", clientKey);
            Persist([.. _rows, row]);
            return row;
        }
    }

    public ScheduledMessage Update(string id, string text, long? sendAt, long now)
    {
        lock (_gate)
        {
            var row = _rows.FirstOrDefault(item => item.Id == id) ?? throw new KeyNotFoundException("定时消息不存在");
            if (row.Status != ScheduledMessageStatus.Pending) throw new InvalidOperationException("只有待发送的定时消息可以修改");
            if (text != null)
            {
                if (text.Trim().Length == 0) throw new ArgumentException("定时消息内容不能为空");
                if (text.Length > MaxTextLength) throw new ArgumentException($"定时消息最多 {MaxTextLength} 个字符");
            }
            if (sendAt is { } at && (at <= 0 || at > now + MaxLeadSeconds || at < now - MissedWindowSeconds))
                throw new ArgumentException("发送时间不在允许范围内");
            var updated = row with { Text = text ?? row.Text, SendAt = sendAt ?? row.SendAt };
            Persist(_rows.Select(item => item.Id == id ? updated : item).ToList());
            return updated;
        }
    }

    public ScheduledMessage Cancel(string id, string reason)
    {
        lock (_gate)
        {
            var row = _rows.FirstOrDefault(item => item.Id == id) ?? throw new KeyNotFoundException("定时消息不存在");
            if (row.Status != ScheduledMessageStatus.Pending) throw new InvalidOperationException("只有待发送的定时消息可以取消");
            var cancelled = row with { Status = ScheduledMessageStatus.Cancelled, LastError = reason ?? "" };
            Persist(_rows.Select(item => item.Id == id ? cancelled : item).ToList());
            return cancelled;
        }
    }

    /// <summary>到点且仍为 pending 的记录。</summary>
    public IReadOnlyList<ScheduledMessage> Due(long now)
    {
        lock (_gate)
            return _rows.Where(row => row.Status == ScheduledMessageStatus.Pending && row.SendAt <= now)
                .OrderBy(row => row.SendAt).ToArray();
    }

    /// <summary>原子认领一条待发记录；false 表示已被其他实例/线程认领。</summary>
    public bool BeginSend(string id)
    {
        lock (_gate)
        {
            var row = _rows.FirstOrDefault(item => item.Id == id);
            if (row is not { Status: ScheduledMessageStatus.Pending }) return false;
            Persist(_rows.Select(item => item.Id == id ? item with { Status = ScheduledMessageStatus.Sending } : item).ToList());
            return true;
        }
    }

    public ScheduledMessage Complete(string id, string status, string runId, string error)
    {
        lock (_gate)
        {
            var row = _rows.FirstOrDefault(item => item.Id == id) ?? throw new KeyNotFoundException("定时消息不存在");
            var done = row with
            {
                Status = status,
                RunId = runId ?? row.RunId,
                LastError = error ?? "",
                SentAt = status == ScheduledMessageStatus.Sent ? DateTimeOffset.UtcNow.ToUnixTimeSeconds() : row.SentAt
            };
            Persist(_rows.Select(item => item.Id == id ? done : item).ToList());
            return done;
        }
    }

    /// <summary>发送失败但允许重试：回到 pending 并顺延到下个重试时间。</summary>
    public ScheduledMessage Postpone(string id, long nextSendAt, string error)
    {
        lock (_gate)
        {
            var row = _rows.FirstOrDefault(item => item.Id == id) ?? throw new KeyNotFoundException("定时消息不存在");
            var postponed = row with
            {
                Status = ScheduledMessageStatus.Pending,
                SendAt = nextSendAt,
                Attempts = row.Attempts + 1,
                LastError = error ?? ""
            };
            Persist(_rows.Select(item => item.Id == id ? postponed : item).ToList());
            return postponed;
        }
    }

    /// <summary>清理 7 天前的终态记录（sent / failed / cancelled）。</summary>
    public void Reap(long now)
    {
        lock (_gate)
        {
            var live = _rows.Where(row => row.Status is ScheduledMessageStatus.Pending or ScheduledMessageStatus.Sending ||
                now - (row.SentAt ?? row.CreatedAt) < TerminalRetentionSeconds).ToList();
            if (live.Count == _rows.Count) return;
            Persist(live);
        }
    }

    private void Persist(List<ScheduledMessage> rows)
    {
        var directory = Path.GetDirectoryName(_path)!;
        IdentityDataDirectory.Ensure(directory);
        var temporary = _path + ".tmp-" + Guid.NewGuid().ToString("N");
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(rows), new UTF8Encoding(false));
            if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(temporary, UnixFileMode.UserRead | UnixFileMode.UserWrite);
            File.Move(temporary, _path, true);
            IdentityDataDirectory.EnsureFile(_path);
        }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
        _rows = rows;
    }
}
