using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.Updates;

public sealed record ServerRelease(int SchemaVersion, string Version, long ReleaseSequence, string Commit,
    string Channel, string MinimumUpdaterVersion, int ProtocolMin, int ProtocolMax, bool RollbackSupported,
    string ReleaseNotes, Dictionary<string, ServerPackage> Packages);
public sealed record ServerPackage(string Url, string Sha256, long Size);
public sealed record UpdatePolicy(string Mode = "notify", int StartHourUtc = 18, int EndHourUtc = 22);
public sealed record UpdateJob(string Id, string Phase, string Message, long Sequence, string Version, bool SourceBuild = false, bool ReleaseSource = false, bool Force = false, bool PullSource = false, string? SourceBranch = null);
public sealed record Installation(string Site, string Pool, string Releases, string Shared, string HealthUrl,
    string DeviceId, string WorkerToken, string Mode = "release", string? ProjectDirectory = null, string? RuntimeDirectory = null, string? UpdaterVersion = null, string? UpdaterAssembly = null, string Host = "iis", bool UserService = false);

public static class UpdateContract
{
    public const string Repository = "bighamx/RemoteTool";
    public static string Platform => OperatingSystem.IsWindows() ? "win-x64-iis" : "linux-" +
        (System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture == System.Runtime.InteropServices.Architecture.Arm64 ? "arm64" : "x64") + "-systemd";
    public const string UpdaterVersion = "1.3.0";
    public static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    // A human review closes tracking for this exact receipt, never rewrites its execution outcome.
    public static bool ReviewedHistoricalRun(JsonNode? run) {
        var review = run?["maintenance_review"];
        return run?["status"]?.ToString() == "acceptance_unknown" &&
            review?["kind"]?.ToString() == "historical_tracking" &&
            review["reviewed_at"] is JsonValue stamp && stamp.TryGetValue<long>(out var at) && at > 0 &&
            new[] { "run_id", "session_id", "turn_id" }.All(key =>
                !string.IsNullOrWhiteSpace(run?[key]?.ToString()) && review[key]?.ToString() == run[key]?.ToString());
    }
    /// <summary>只丢了受理确认、且超过 6 小时没有任何进展的任务，不可能仍在执行；用于放行维护而不阻塞更新。</summary>
    public static bool StaleAcceptance(JsonNode? run) {
        if (run?["status"]?.ToString() != "acceptance_unknown") return false;
        // 已有人工核对痕迹（哪怕对不上）时保持阻塞，交给核对流程处理
        if (run?["maintenance_review"] != null) return false;
        var stamp = run?["created_at"]?.GetValue<long>() ?? run?["enqueued_at"]?.GetValue<long>() ?? 0;
        return stamp > 0 && DateTimeOffset.UtcNow.ToUnixTimeSeconds() - stamp > 6 * 3600;
    }
    public static bool RunBlocksMaintenance(JsonNode? run) => run?["status"]?.ToString() is "started" or "submitting" or "queued" or "running" ||
        run?["status"]?.ToString() == "acceptance_unknown" && !ReviewedHistoricalRun(run);
    public static ServerRelease Verify(byte[] data) {
        var release = JsonSerializer.Deserialize<ServerRelease>(data, Json) ?? throw new InvalidDataException("更新清单为空");
        if (release.SchemaVersion != 1 || release.ReleaseSequence <= 0 || release.Channel != "stable" ||
            !release.RollbackSupported || release.ProtocolMin > 1 || release.ProtocolMax < 1 ||
            !System.Version.TryParse(release.MinimumUpdaterVersion, out var minimum) || minimum > System.Version.Parse(UpdaterVersion) ||
            release.Commit.Length != 40 || !release.Commit.All(Uri.IsHexDigit) || !release.Packages.ContainsKey(Platform))
            throw new InvalidDataException("发行版本或协议不兼容");
        var package = release.Packages[Platform];
        var uri = new Uri(package.Url);
        if (uri.Scheme != "https" || uri.Host != "github.com" || !uri.AbsolutePath.StartsWith("/" + Repository + "/releases/download/server-", StringComparison.Ordinal) ||
            package.Size is < 1 or > 1_073_741_824 || package.Sha256.Length != 64 || !package.Sha256.All(Uri.IsHexDigit))
            throw new InvalidDataException("发行包地址、大小或校验值无效");
        return release;
    }
    public static void Write<T>(string path, T value) {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try { File.WriteAllText(temporary, JsonSerializer.Serialize(value, Json)); File.Move(temporary, path, true); }
        finally { if (File.Exists(temporary)) File.Delete(temporary); }
    }
    public static T Read<T>(string path) => JsonSerializer.Deserialize<T>(File.ReadAllText(path), Json)!;
    public static void WriteJob(string root, UpdateJob job) {
        if (!Guid.TryParseExact(job.Id, "N", out _)) throw new InvalidDataException("更新任务标识无效");
        Write(Path.Combine(root, "jobs", job.Id, "status.json"), job);
        Write(Path.Combine(root, "job.json"), job);
    }
    public static bool InWindow(UpdatePolicy policy, int hour) => policy.StartHourUtc == policy.EndHourUtc ||
        (policy.StartHourUtc < policy.EndHourUtc ? hour >= policy.StartHourUtc && hour < policy.EndHourUtc : hour >= policy.StartHourUtc || hour < policy.EndHourUtc);
    public static void Extract(string archive, string destination) {
        var root = Path.GetFullPath(destination) + Path.DirectorySeparatorChar;
        using var zip = ZipFile.OpenRead(archive);
        long expanded = 0;
        foreach (var item in zip.Entries) {
            var name = item.FullName.Replace('\\', '/');
            var path = Path.GetFullPath(Path.Combine(root, name));
            var leaf = Path.GetFileName(path);
            if (!path.StartsWith(root, OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal) || name.Contains(':') ||
                (item.ExternalAttributes >> 16 & 0xF000) == 0xA000 ||
                leaf.StartsWith("appsettings", StringComparison.OrdinalIgnoreCase) || leaf.Equals("web.config", StringComparison.OrdinalIgnoreCase) ||
                leaf.Equals("auth.json",StringComparison.OrdinalIgnoreCase) || leaf == ".env" ||
                new[] { ".db", ".sqlite", ".sqlite3", ".db-wal", ".db-shm", ".sqlite-wal", ".sqlite-shm" }.Any(suffix => leaf.EndsWith(suffix,StringComparison.OrdinalIgnoreCase)) ||
                name.Split('/').Any(s => s is ".git" or "shared" or "updates" or "uploads" or "attachments" or "logs" or "data"))
                throw new InvalidDataException("发行包包含受保护路径");
            expanded = checked(expanded + item.Length);
            if (expanded > 3L * 1024 * 1024 * 1024) throw new InvalidDataException("发行包解压大小超限");
            if (name.EndsWith('/')) { Directory.CreateDirectory(path); continue; }
            Directory.CreateDirectory(Path.GetDirectoryName(path)!); item.ExtractToFile(path, false);
        }
        if (!File.Exists(Path.Combine(root, "WebApplication.dll"))) throw new InvalidDataException("发行包缺少服务端程序");
    }
}
