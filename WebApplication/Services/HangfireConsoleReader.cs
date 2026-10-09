using Hangfire.Storage;
using Hangfire.Storage.Monitoring;
using Hangfire.Common;
using System.Globalization;
using System.Text.Json;

namespace ChuckieHelper.WebApi.Services;

/// <summary>Bounded, read-only adapter for the pinned Hangfire.Console 1.4.3 storage format.</summary>
public static class HangfireConsoleReader
{
    public sealed record Line(int Index, string Type, DateTime Timestamp, string Text, string Color,
        string ProgressId = null, string Name = null, double? Progress = null, bool Truncated = false);
    public sealed record Page(string Attempt, int Offset, int NextOffset, int Total, bool HasMore, bool Reset,
        IReadOnlyList<Line> Data, double? Progress);

    public static string AttemptId(DateTime started) => ((started.Ticks - DateTime.UnixEpoch.Ticks) / TimeSpan.TicksPerMillisecond).ToString(CultureInfo.InvariantCulture);
    public static DateTime AttemptStarted(StateHistoryDto history) {
        // Hangfire.Console uses Processing.StartedAt, which can precede CreatedAt.
        if(history.Data?.TryGetValue("StartedAt",out var value)==true)
            try{return JobHelper.DeserializeDateTime(value);}catch(Exception error) when(error is FormatException or ArgumentException or Newtonsoft.Json.JsonException) { }
        return history.CreatedAt;
    }

    public static Page Read(IStorageConnection connection, string jobId, DateTime started, int? offset, int count)
    {
        if (connection is not JobStorageConnection storage) throw new NotSupportedException("Console storage does not support paged reads");
        var attempt = AttemptId(started);
        var milliseconds = long.Parse(attempt, CultureInfo.InvariantCulture);
        var encoded = new string(milliseconds.ToString("x11", CultureInfo.InvariantCulture).Reverse().ToArray()) + jobId;
        var set = "console:" + encoded;
        var hash = "console:refs:" + encoded;
        var total = (int)Math.Min(int.MaxValue, storage.GetSetCount(set));
        if (total == 0) { set = encoded; hash = encoded; total = (int)Math.Min(int.MaxValue, storage.GetSetCount(set)); }
        count = Math.Clamp(count, 1, 500);
        var reset = offset is < 0 || offset > total;
        var start = offset == null || reset ? Math.Max(0, total - count) : offset.Value;
        var values = (start < total ? storage.GetRangeFromSet(set, start, Math.Min(total - 1, start + count - 1)) : null) ?? new List<string>();
        var rows = new List<Line>();
        for (var i = 0; i < values.Count; i++) {
            try {
                using var document = JsonDocument.Parse(values[i]); var line = document.RootElement;
                var seconds = line.GetProperty("t").GetDouble();
                var time = double.IsFinite(seconds) && Math.Abs(seconds) < 315_360_000 ? started.AddSeconds(seconds) : started;
                var text = line.GetProperty("s").GetString() ?? "";
                if (line.TryGetProperty("r", out var reference) && reference.ValueKind == JsonValueKind.True)
                    text = storage.GetValueFromHash(hash, text) ?? "[日志内容已过期]";
                var color = line.TryGetProperty("c", out var c) ? c.GetString() : null;
                var length = Math.Min(text.Length, 16_384);
                if (length > 0 && char.IsHighSurrogate(text[length - 1])) length--;
                var progress = line.TryGetProperty("p", out var p) && p.TryGetDouble(out var value) && double.IsFinite(value) ? (double?)Math.Clamp(value, 0, 100) : null;
                rows.Add(new Line(start + i, progress.HasValue ? "progress" : "text", DateTime.SpecifyKind(time, DateTimeKind.Utc),
                    text[..length], color, progress.HasValue ? text : null,
                    line.TryGetProperty("n", out var name) ? name.GetString() : null, progress, text.Length > 16_384));
            } catch (Exception error) when (error is JsonException or InvalidOperationException or KeyNotFoundException or FormatException) {
                rows.Add(new Line(start + i, "text", DateTime.SpecifyKind(started, DateTimeKind.Utc), "[无法读取这条日志记录]", null));
            }
        }
        var progressValue = storage.GetValueFromHash("console:refs:" + encoded, "progress");
        double? overall = double.TryParse(progressValue, NumberStyles.Float, CultureInfo.InvariantCulture, out var overallValue) && double.IsFinite(overallValue) ? Math.Clamp(overallValue, 0, 100) : null;
        var next = start + values.Count;
        return new Page(attempt, start, next, total, next < total, reset, rows, overall);
    }
}
