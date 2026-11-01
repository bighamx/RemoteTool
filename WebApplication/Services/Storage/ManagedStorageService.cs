using System.Collections.Concurrent;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Configuration;

namespace RemoteTool.WebApi.Services.Storage;

public sealed record StorageRow(string Id, string Category, string Kind, string Agent, string Profile, string Session,
    string Name, string Group, string Source, long Bytes, long ModifiedAt, bool Protected, string Reason);
public sealed class StorageFilter {
    public string Category { get; set; } = "";
    public string Kind { get; set; } = "";
    public string Agent { get; set; } = "";
    public string Session { get; set; } = "";
    public string Search { get; set; } = "";
    public string Sort { get; set; } = "size_desc";
    public int OlderDays { get; set; }
    public int Offset { get; set; }
    public int Limit { get; set; } = 100;
}
public sealed record StorageCategory(string Id, string Name, long Bytes, int Count, int ProtectedCount);
public sealed record StorageSelection(string Snapshot, string[] Ids, string Action);
public sealed record StorageExecution(string Plan);

public sealed class ManagedStorageService
{
    private sealed record Candidate(StorageRow Row, string Root, string Path, long Ticks, TrashEntry? Trash = null);
    private sealed record Snapshot(string Owner, DateTime At, Dictionary<string, Candidate> Files);
    private sealed record Plan(string Owner, DateTime At, string Action, Candidate[] Files) { public object? Result; }
    private sealed record TrashEntry(string Id, string RootKey, string Relative, StorageRow Original, long DeletedAt);
    private readonly string data, trash;
    private readonly Dictionary<string, string> roots;
    private readonly Func<string, string, string, long?> activeAge;
    private readonly Func<string, string, string, bool> nativeActive = (_,_,_) => false;
    private readonly Func<string, string, Dictionary<string,string>> labels = (_,_) => new();
    private readonly ConcurrentDictionary<string, Snapshot> snapshots = new();
    private readonly ConcurrentDictionary<string, Plan> plans = new();
    private readonly SemaphoreSlim mutations = new(1, 1);
    public ManagedStorageService(RunRegistry runs, IConfiguration configuration, HermesProfiles profiles, Func<string,string,string,bool>? nativeActive = null) : this(RemoteToolPaths.ProgramData,
        (agent, profile, session) => {
            var entry = runs.QueryEntry(agent == "hermes" && profile != "default" ? "hermes@" + profile : agent, session);
            return entry is null ? null : DateTimeOffset.UtcNow.ToUnixTimeSeconds() - entry.Value.UpdatedAt;
        }) {
        this.nativeActive = nativeActive ?? ((_,_,_)=>false);
        labels = (agent, profile) => {
            try {
                var home = agent == "codex" ? configuration["Codex:Home"] : profiles.HomeFor(profile);
                if (agent == "codex" && string.IsNullOrWhiteSpace(home)) {
                    var connection = Path.Combine(RemoteToolPaths.CodexBridge,"connection.json");
                    if(File.Exists(connection)) home = JsonDocument.Parse(File.ReadAllText(connection)).RootElement.GetProperty("home").GetString();
                }
                if(string.IsNullOrWhiteSpace(home)) return new();
                var db = Path.Combine(home,agent == "codex" ? "state_5.sqlite" : "state.db");
                if(!File.Exists(db)) return new();
                using var c = new SQLite.SQLiteConnection(db,SQLite.SQLiteOpenFlags.ReadOnly);
                return c.Query<SessionLabel>(agent == "codex" ? "SELECT id,title FROM threads LIMIT 10000" : "SELECT id,title FROM sessions LIMIT 10000")
                    .Where(x=>!string.IsNullOrWhiteSpace(x.id)).GroupBy(x=>x.id).ToDictionary(x=>x.Key,x=>x.First().title??"");
            } catch(Exception e) when(e is IOException or UnauthorizedAccessException or JsonException or SQLite.SQLiteException or ArgumentException or KeyNotFoundException){return new();}
        };
    }
    public sealed class SessionLabel { public string id {get;set;} = ""; public string? title {get;set;} }
    internal ManagedStorageService(string directory, Func<string, string, string, long?>? active = null) {
        data = Path.GetFullPath(directory); trash = Path.Combine(data, "storage-management", "trash");
        roots = new() { ["codex"] = Path.Combine(data, "codex-attachments"), ["hermes"] = Path.Combine(data, "hermes-attachments"), ["logs"] = Path.Combine(data, "logs") };
        this.activeAge = active ?? ((_, _, _) => null);
    }
    private Snapshot Require(string token, string owner) => snapshots.TryGetValue(token, out var value) && value.Owner == owner && DateTime.UtcNow - value.At < TimeSpan.FromMinutes(10)
        ? value : throw new ArgumentException("列表已过期，请重新扫描");
    private static long Ms(DateTime value) => new DateTimeOffset(DateTime.SpecifyKind(value, DateTimeKind.Utc)).ToUnixTimeMilliseconds();
    // 注册表只记「发起时间」，不保证收到终态（客户端被杀/服务重启会漏 Clear）。
    // 会话自身报告的运行状态是权威信号；登记只有短暂的「刚提交还没开始」窗口。
    private const long BusyWindowSeconds = 90;
    private string? BusyReason(StorageRow row) {
        if (row.Agent.Length == 0) return null;
        long? age;
        try { age = activeAge(row.Agent, row.Profile, row.Session); }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or ArgumentException) { return null; }
        if (age is null) return null;
        if (NativeBusy(row)) return "会话正在执行任务，结束后可清理";
        return age <= BusyWindowSeconds ? "任务刚刚提交，稍后可清理" : null;
    }
    private bool Busy(StorageRow row) => BusyReason(row) is not null;
    private bool NativeBusy(StorageRow row) {
        if(row.Agent.Length==0)return false;
        try{return nativeActive(row.Agent,row.Profile,row.Session);}catch(Exception e)when(e is IOException or UnauthorizedAccessException or ArgumentException){return false;}
    }
    public object Scan(string owner, CancellationToken ct) {
        var now = DateTime.UtcNow; var files = new Dictionary<string, Candidate>(); var partial = false;
        var titleCache = new Dictionary<string,Dictionary<string,string>>();
        foreach (var (key, root) in roots) {
            foreach (var path in StorageFiles.Walk(root, ct)) {
                ct.ThrowIfCancellationRequested();
                if (files.Count >= 100_000) { partial = true; break; }
                if (!StorageFiles.Safe(root, path) || Path.GetFileName(path).StartsWith('.')) continue;
                var relative = Path.GetRelativePath(root, path); var parts = relative.Split(Path.DirectorySeparatorChar);
                var profile = "default"; var at = 0;
                if (key == "hermes" && parts.Length >= 4 && parts[0] == ".profiles") { profile = parts[1]; at = 2; }
                if (key != "logs" && parts.Length <= at + 1) continue;
                var session = key == "logs" ? "" : parts[at];
                var labelKey = key+":"+profile;
                if(key!="logs" && !titleCache.ContainsKey(labelKey)) titleCache[labelKey] = labels(key,profile);
                var sessionLabel = key!="logs" && titleCache[labelKey].TryGetValue(session,out var title) && !string.IsNullOrWhiteSpace(title) ? title : session;
                if (key == "logs" && Path.GetExtension(path).ToLowerInvariant() is not (".log" or ".txt")) continue;
                try {
                    var info = new FileInfo(path); if (!info.Exists) continue;
                    var name = info.Name; if (name.Length > 33 && name[32] == '_') name = name[33..];
                    var source = key == "logs" ? "诊断日志" : parts.ElementAtOrDefault(at + 1) == "outbox" ? "生成文件" : parts.ElementAtOrDefault(at + 1) == "media" ? "附件快照" : "上传附件";
                    var protectedItem = info.LastWriteTimeUtc > now.AddMinutes(-2) || key == "logs" && info.LastWriteTimeUtc > now.AddDays(-1);
                    var row = new StorageRow(Guid.NewGuid().ToString("N"), key == "logs" ? "logs" : "attachments", StorageFiles.Kind(name), key == "logs" ? "" : key,
                        profile, session, name, key == "logs" ? "服务端日志" : $"{key} · {profile} · {sessionLabel}", source, info.Length, Ms(info.LastWriteTimeUtc), protectedItem,
                        protectedItem ? "最近写入，稍后可清理" : "");
                    if (BusyReason(row) is { } reason) row = row with { Protected = true, Reason = reason };
                    files[row.Id] = new(row, root, path, info.LastWriteTimeUtc.Ticks);
                } catch (IOException) { partial = true; } catch (UnauthorizedAccessException) { partial = true; }
            }
        }
        foreach (var manifest in StorageFiles.Walk(trash, ct).Where(p => Path.GetFileName(p) == "entry.json")) {
            if (files.Count >= 100_000) { partial = true; break; }
            try {
                if (!StorageFiles.Safe(trash, manifest)) continue;
                var entry = JsonSerializer.Deserialize<TrashEntry>(File.ReadAllText(manifest));
                if (entry == null || !roots.ContainsKey(entry.RootKey)) continue;
                var payload = Path.Combine(Path.GetDirectoryName(manifest)!, "payload"); var info = new FileInfo(payload);
                if (!info.Exists || !StorageFiles.Safe(trash, payload)) continue;
                var row = entry.Original with { Id = Guid.NewGuid().ToString("N"), Category = "trash", Bytes = info.Length, ModifiedAt = entry.DeletedAt, Protected = false, Reason = "", Source = "回收站 · " + entry.Original.Source };
                files[row.Id] = new(row, trash, payload, info.LastWriteTimeUtc.Ticks, entry);
            } catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException) { partial = true; }
        }
        foreach (var key in snapshots.Where(x => now - x.Value.At > TimeSpan.FromMinutes(10)).Select(x => x.Key)) snapshots.TryRemove(key, out _);
        if (snapshots.Count >= 8) snapshots.TryRemove(snapshots.MinBy(x => x.Value.At).Key, out _);
        var token = Guid.NewGuid().ToString("N"); snapshots[token] = new(owner, now, files);
        var names = new Dictionary<string, string> { ["attachments"] = "会话附件", ["logs"] = "诊断日志", ["trash"] = "回收站" };
        var categories = names.Select(pair => new StorageCategory(pair.Key, pair.Value, files.Values.Where(x => x.Row.Category == pair.Key).Sum(x => x.Row.Bytes),
            files.Values.Count(x => x.Row.Category == pair.Key), files.Values.Count(x => x.Row.Category == pair.Key && x.Row.Protected))).ToArray();
        return new { snapshot = token, createdAt = Ms(now), totalBytes = files.Values.Sum(x => x.Row.Bytes), partial, categories,
            note = "仅管理 RemoteTool 的附件和诊断日志。原生 Codex/Hermes 会话、数据库、配置和凭据不参与清理。回收站仍占空间，永久删除后才释放。" };
    }
    public object Items(string owner, string token, StorageFilter filter) {
        var source = Require(token, owner).Files.Values.Select(x => x.Row);
        if (!string.IsNullOrEmpty(filter.Category)) source = source.Where(x => x.Category == filter.Category);
        else source = source.Where(x => x.Category != "trash");
        if (!string.IsNullOrEmpty(filter.Kind)) source = source.Where(x => x.Kind == filter.Kind);
        if (!string.IsNullOrEmpty(filter.Agent)) source = source.Where(x => x.Agent == filter.Agent);
        if (!string.IsNullOrEmpty(filter.Session)) source = source.Where(x => x.Session == filter.Session);
        if (filter.OlderDays > 0) { var cutoff = Ms(DateTime.UtcNow.AddDays(-Math.Min(filter.OlderDays, 3650))); source = source.Where(x => x.ModifiedAt < cutoff); }
        var search = (filter.Search ?? "").Trim(); if (search.Length > 0) source = source.Where(x => (x.Name + " " + x.Group + " " + x.Session).Contains(search, StringComparison.OrdinalIgnoreCase));
        source = filter.Sort switch { "oldest" => source.OrderBy(x => x.ModifiedAt), "newest" => source.OrderByDescending(x => x.ModifiedAt),
            "size_asc" => source.OrderBy(x => x.Bytes), "name" => source.OrderBy(x => x.Name), _ => source.OrderByDescending(x => x.Bytes) };
        var all = source.ToArray(); var offset = Math.Max(0, filter.Offset); var limit = Math.Clamp(filter.Limit, 1, 200);
        return new { data = all.Skip(offset).Take(limit).ToArray(), total = all.Length, bytes = all.Sum(x => x.Bytes), hasMore = offset + limit < all.Length };
    }
    public object Prepare(string owner, StorageSelection selection) {
        if (selection.Action is not ("trash" or "purge" or "restore")) throw new ArgumentException("无效清理方式");
        if (selection.Ids == null || selection.Ids.Length is < 1 or > 500) throw new ArgumentException("每次请选择 1 至 500 项");
        var snapshot = Require(selection.Snapshot, owner);
        var files = selection.Ids.Distinct().Select(id => snapshot.Files.TryGetValue(id, out var item) ? item : throw new ArgumentException("所选项目不属于当前扫描")).ToArray();
        if (files.Any(x => x.Row.Protected || Busy(x.Row))) throw new ArgumentException("所选项目正在使用或尚未同步，请取消这些项目后重试");
        var externallyBusy=files.Select(x=>x.Row).DistinctBy(x=>(x.Agent,x.Profile,x.Session)).Where(NativeBusy).Select(x=>x.Group).Take(3).ToArray();
        if(externallyBusy.Length>0)throw new ArgumentException("请取消正在其他客户端执行的会话后重试："+string.Join("；",externallyBusy));
        if (files.Any(x => selection.Action == "trash" ? x.Trash != null : x.Trash == null)) throw new ArgumentException("恢复和永久删除仅适用于回收站；其他文件请先移入回收站");
        foreach (var key in plans.Where(x => DateTime.UtcNow - x.Value.At > TimeSpan.FromMinutes(10)).Select(x => x.Key)) plans.TryRemove(key, out _);
        if (plans.Count > 64) throw new ArgumentException("待确认操作过多，请稍后重试");
        var token = Guid.NewGuid().ToString("N"); plans[token] = new(owner, DateTime.UtcNow, selection.Action, files);
        return new { plan = token, action = selection.Action, count = files.Length, bytes = files.Sum(x => x.Row.Bytes),
            warning = selection.Action == "trash" ? "移入回收站后可恢复，但附件会暂时无法访问，且不会立即释放空间。" : selection.Action == "purge" ? "永久删除不可撤销，相关会话将无法再访问这些附件。" : "恢复到原位置；已有同名文件时不会覆盖。" };
    }
    public async Task<object> Execute(string owner, string token) {
        if (!plans.TryGetValue(token, out var plan) || plan.Owner != owner) throw new ArgumentException("操作不存在，请刷新后核对");
        await mutations.WaitAsync();
        try {
            if (plan.Result != null) return plan.Result;
            if (DateTime.UtcNow - plan.At > TimeSpan.FromMinutes(10)) throw new ArgumentException("操作已过期，请重新确认");
            var processed = 0; long bytes = 0; var failures = new List<object>();
            var nativeBusy=plan.Files.Select(x=>x.Row).DistinctBy(x=>(x.Agent,x.Profile,x.Session)).Where(NativeBusy).Select(x=>(x.Agent,x.Profile,x.Session)).ToHashSet();
            foreach (var candidate in plan.Files) try {
                var row = candidate.Row;
                if (!StorageFiles.Safe(candidate.Root, candidate.Path) || Busy(row) || nativeBusy.Contains((row.Agent,row.Profile,row.Session))) throw new IOException("路径已改变或会话正在使用");
                var info = new FileInfo(candidate.Path);
                if (!info.Exists || info.Length != row.Bytes || info.LastWriteTimeUtc.Ticks != candidate.Ticks) throw new IOException("文件已变化，请重新扫描");
                // Refuse open readers/writers on platforms honoring FileShare.
                using var handle = new FileStream(candidate.Path, FileMode.Open, FileAccess.Read, FileShare.Delete);
                if (plan.Action == "trash") {
                    var folder = Path.Combine(trash, Guid.NewGuid().ToString("N"));
                    if (!StorageFiles.Safe(data, folder)) throw new IOException("回收站路径不可用");
                    Directory.CreateDirectory(folder);
                    var rootKey = row.Agent.Length > 0 ? row.Agent : "logs";
                    var entry = new TrashEntry(Path.GetFileName(folder), rootKey, Path.GetRelativePath(candidate.Root, candidate.Path), row, Ms(DateTime.UtcNow));
                    StorageFiles.WriteJson(Path.Combine(folder, "entry.json"), entry);
                    if (row.Agent.Length > 0) AttachmentDeletionLedger.Set(candidate.Root, candidate.Path, true);
                    File.Move(candidate.Path, Path.Combine(folder, "payload"));
                } else if (plan.Action == "restore") {
                    var entry = candidate.Trash!; var root = roots[entry.RootKey]; var destination = Path.GetFullPath(Path.Combine(root, entry.Relative));
                    if (!StorageFiles.Safe(root, destination) || File.Exists(destination) || Busy(entry.Original)) throw new IOException("原位置已存在文件、会话正在使用或路径不可恢复");
                    Directory.CreateDirectory(Path.GetDirectoryName(destination)!); File.Move(candidate.Path, destination);
                    if (entry.Original.Agent.Length > 0) AttachmentDeletionLedger.Set(root, destination, false);
                } else File.Delete(candidate.Path);
                processed++; bytes += row.Bytes;
                if (plan.Action != "trash") {
                    var folder = Path.GetDirectoryName(candidate.Path)!;
                    try { File.Delete(Path.Combine(folder, "entry.json")); Directory.Delete(folder, false); } catch (IOException) { }
                }
            } catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException or ArgumentException) { failures.Add(new { id = candidate.Row.Id, name = candidate.Row.Name, message = e.Message }); }
            return plan.Result = new { processed, bytes, freedBytes = plan.Action == "purge" ? bytes : 0, failed = failures.Count, failures };
        } finally { mutations.Release(); }
    }
}
