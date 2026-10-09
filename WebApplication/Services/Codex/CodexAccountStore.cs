using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;

namespace ChuckieHelper.WebApi.Services.Codex;

internal sealed class CodexAccountStore
{
    private readonly string home, folder, path;
    private readonly Mutex mutex;
    public CodexAccountStore(string home, string folder) {
        this.home = home; this.folder = folder; path = Path.Combine(folder, "accounts.json");
        Directory.CreateDirectory(folder);
        mutex = new Mutex(false, "Local\\ChuckieCodexAccounts-" + CodexJson.Hash(path)[..20]);
    }
    private IDisposable Lock() {
        try { if (!mutex.WaitOne(TimeSpan.FromSeconds(10))) throw new CodexError("账户库正在使用，请稍后重试", 409); }
        catch (AbandonedMutexException) { }
        return new Release(mutex);
    }
    private sealed class Release(Mutex mutex) : IDisposable { public void Dispose() => mutex.ReleaseMutex(); }
    private JsonObject Read() {
        var value = CodexJson.Read(path);
        value["version"] ??= 2; value["accounts"] ??= new JsonObject(); return value;
    }
    public JsonObject CurrentAuth() => CodexJson.Read(Path.Combine(home, "auth.json"));
    public static JsonObject Identity(JsonObject auth) {
        var tokens = auth["tokens"];
        var parts = tokens.S("id_token").Split('.');
        if (parts.Length != 3 || parts.Any(string.IsNullOrEmpty)) throw new CodexError("登录记录缺少合法 id_token");
        JsonNode payload;
        try { var encoded = parts[1].Replace('-', '+').Replace('_', '/'); payload = JsonNode.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(encoded.PadRight((encoded.Length + 3) / 4 * 4, '='))))!; }
        catch (Exception) { throw new CodexError("登录记录的 id_token 格式无效"); }
        var claims = payload["https://api.openai.com/auth"];
        var workspace = claims.S("chatgpt_account_id"); var email = payload.S("email");
        if (workspace.Length == 0 || email.Length == 0 || tokens.S("refresh_token").Length == 0) throw new CodexError("登录记录缺少邮箱、工作空间或刷新令牌");
        if (tokens.S("account_id").Length > 0 && tokens.S("account_id") != workspace) throw new CodexError("凭据的工作空间标识不一致");
        var access = tokens.S("access_token").Split('.');
        if (access.Length == 3) {
            try {
                var encoded = access[1].Replace('-', '+').Replace('_', '/');
                var routed = JsonNode.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(encoded.PadRight((encoded.Length + 3) / 4 * 4, '='))))?["https://api.openai.com/auth"].S("chatgpt_account_id");
                if (!string.IsNullOrEmpty(routed) && routed != workspace) throw new CodexError("访问令牌与目标工作空间不一致，请重新保存登录", 409);
            } catch (Exception error) when (error is FormatException or System.Text.Json.JsonException) { throw new CodexError("访问令牌格式无效", 409); }
        }
        var plan = claims.S("chatgpt_plan_type");
        return CodexJson.Obj(("email", email), ("workspace_id", workspace), ("subject", payload.S("sub")), ("plan_type", plan),
            ("workspace_name", plan is "business" or "team" or "enterprise" or "edu" ? "团队工作空间" : "个人工作空间"));
    }
    private static bool Same(JsonNode row, JsonNode identity) => row.S("email") == identity.S("email") && row.S("chatgpt_account_id") == identity.S("workspace_id");
    public string Capture(JsonObject auth, string name = "") {
        var identity = Identity(auth);
        using var guard = Lock();
        var data = Read(); var accounts = data["accounts"]!.AsObject();
        var key = accounts.FirstOrDefault(x => Same(x.Value!, identity)).Key ?? Guid.NewGuid().ToString();
        var previous = accounts[key];
        var row = identity.DeepClone().AsObject();
        row["account_id"] = key; row["chatgpt_account_id"] = identity.S("workspace_id");
        row["name"] = name.Length > 0 ? name : previous.S("name", identity.S("workspace_name"));
        row["auth"] = auth.DeepClone(); row["token_updated_at_ms"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        accounts[key] = row; CodexJson.Atomic(path, data); return key;
    }
    public JsonObject List() {
        JsonObject current = null;
        try { current = Identity(CurrentAuth()); } catch (CodexError) { }
        // Recover the current workspace's newer credentials after an external switch/refresh.
        // Do not mutate auth.json or rotate a token during this read.
        if (current != null) {
            using var sync = Lock();
            var currentAuth = CurrentAuth(); var syncedData = Read();
            var row = syncedData["accounts"]!.AsObject().FirstOrDefault(x => Same(x.Value!, current)).Value as JsonObject;
            var writtenAt = new DateTimeOffset(File.GetLastWriteTimeUtc(Path.Combine(home, "auth.json"))).ToUnixTimeMilliseconds();
            if (row != null && writtenAt >= row.L("token_updated_at_ms") && row["auth"]?.ToJsonString() != currentAuth.ToJsonString()) {
                row["auth"] = currentAuth.DeepClone(); row["token_updated_at_ms"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(); CodexJson.Atomic(path, syncedData);
            } else if (!File.Exists(path)) Capture(currentAuth);
        }
        using var guard = Lock();
        var data = Read(); var rows = new JsonArray(); string selected = null;
        foreach (var entry in data["accounts"]!.AsObject()) {
            var row = entry.Value!;
            if (current != null && Same(row, current)) selected = entry.Key;
            rows.Add(CodexJson.Obj(("id", entry.Key), ("name", row.S("name", row.S("workspace_name"))), ("email", row.S("email")),
                ("workspace_id", row.S("chatgpt_account_id")), ("workspace_name", row.S("workspace_name")), ("plan_type", row.S("plan_type")), ("authenticated", true), ("auth_type", "chatgpt")));
        }
        return CodexJson.Obj(("current", selected), ("current_identity", current), ("data", rows));
    }
    public void Use(string id) {
        using var guard = Lock();
        var data = Read(); var accounts = data["accounts"]!.AsObject();
        var target = accounts[id] as JsonObject ?? throw new CodexError("登录记录不存在", 404);
        var targetAuth = target["auth"]?.DeepClone() as JsonObject ?? throw new CodexError("此记录需要重新授权");
        Identity(targetAuth);
        var source = CurrentAuth();
        if (source.Count > 0) {
            var info = Identity(source);
            var owner = accounts.FirstOrDefault(x => Same(x.Value!, info)).Value as JsonObject;
            if (owner == null) throw new CodexError("请先保存电脑当前登录；auth.json 未改变", 409);
            owner["auth"] = source.DeepClone(); owner["token_updated_at_ms"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            if (Same(target, info)) targetAuth = source.DeepClone().AsObject();
        }
        CodexJson.Atomic(path, data); // Recover rotated source credentials first.
        if (source.Count > 0) CodexJson.Atomic(Path.Combine(folder, "backups", "auth-" + DateTime.UtcNow.ToString("yyyyMMdd-HHmmss") + "-" + Guid.NewGuid().ToString("N")[..8] + ".json"), source);
        targetAuth["tokens"]!["account_id"] = target.S("chatgpt_account_id");
        CodexJson.Atomic(Path.Combine(home, "auth.json"), targetAuth);
        if (Identity(CurrentAuth()).S("workspace_id") != target.S("chatgpt_account_id")) {
            if (source.Count > 0) CodexJson.Atomic(Path.Combine(home, "auth.json"), source);
            throw new CodexError("切换核对失败，已恢复登录");
        }
        data["default_account_id"] = id; CodexJson.Atomic(path, data);
    }
    public void ValidateWorkspaceSwitch(string id) {
        using var guard = Lock();
        var data = Read(); var rows = data["accounts"]!.AsObject();
        var target = rows[id] as JsonObject ?? throw new CodexError("工作空间登录记录不存在", 404);
        var targetAuth = target["auth"] as JsonObject ?? throw new CodexError("该工作空间需要重新登录");
        var targetIdentity = Identity(targetAuth);
        // No current auth.json (custom provider active or logged out) means there is no
        // live session to protect: switching back to a saved workspace must be allowed.
        JsonObject sourceIdentity = null;
        try { sourceIdentity = Identity(CurrentAuth()); } catch (CodexError) { }
        if (sourceIdentity != null) {
            if (sourceIdentity.S("email") != targetIdentity.S("email")) throw new CodexError("此操作只切换当前邮箱的个人／团队工作空间", 409);
            if (!rows.Any(x => Same(x.Value!, sourceIdentity))) throw new CodexError("请先保存当前工作空间登录；未结束进程或修改凭据", 409);
        }
    }
    public JsonObject WorkspaceAuth(string id) {
        using var guard = Lock();
        var row = Read()["accounts"]![id] as JsonObject ?? throw new CodexError("工作空间登录记录不存在", 404);
        var saved = row["auth"]?.DeepClone() as JsonObject ?? throw new CodexError("该工作空间需要重新登录");
        var identity = Identity(saved); var current = CurrentAuth();
        // Missing current login (custom provider or logged out) has no email to match;
        // serve the saved workspace credentials so usage and switching still work.
        if (current.Count > 0) {
            var source = Identity(current);
            if (identity.S("email") != source.S("email")) throw new CodexError("只能查询当前账户的工作空间", 403);
            return identity.S("workspace_id") == source.S("workspace_id") ? current : saved;
        }
        return saved;
    }
    public void RecoverWorkspaceAuth(string id, JsonObject original, JsonObject refreshed) {
        var identity = Identity(refreshed); var previous = Identity(original);
        if (identity.S("email") != previous.S("email") || identity.S("workspace_id") != previous.S("workspace_id")) throw new CodexError("用量查询返回了不同工作空间的登录", 409);
        if (refreshed.ToJsonString() == original.ToJsonString()) return;
        using var guard = Lock();
        var data = Read(); var row = data["accounts"]![id] as JsonObject;
        // Do not overwrite a newer login or recreate a record removed during the query.
        if (row == null || row["auth"]?.ToJsonString() != original.ToJsonString()) return;
        row["auth"] = refreshed.DeepClone(); row["token_updated_at_ms"] = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        if (CurrentAuth().ToJsonString() == original.ToJsonString()) CodexJson.Atomic(Path.Combine(home, "auth.json"), refreshed);
        CodexJson.Atomic(path, data);
    }
    public void Restore(JsonObject auth) {
        using var guard = Lock();
        CodexJson.Atomic(Path.Combine(home, "auth.json"), auth);
        var data = Read(); var identity = Identity(auth);
        data["default_account_id"] = data["accounts"]!.AsObject().FirstOrDefault(x => Same(x.Value!, identity)).Key;
        CodexJson.Atomic(path, data);
    }
    public void Remove(string id) {
        using var guard = Lock(); var data = Read();
        if (!data["accounts"]!.AsObject().Remove(id)) throw new CodexError("登录记录不存在", 404);
        if (data.S("default_account_id") == id) data["default_account_id"] = null;
        CodexJson.Atomic(path, data); // Native auth.json intentionally stays unchanged.
    }
    public static bool DesktopBusy(IEnumerable<int> excluded) {
        if (!OperatingSystem.IsWindows()) return false;
        var snapshot = CreateToolhelp32Snapshot(2, 0);
        if (snapshot == new IntPtr(-1)) throw new CodexError("无法检查其他 Codex 进程；未执行切换", 409);
        var exclusion = excluded.ToHashSet(); var entry = new ProcessEntry { Size = (uint)Marshal.SizeOf<ProcessEntry>() };
        try {
            var next = Process32First(snapshot, ref entry);
            while (next) {
                if (!exclusion.Contains((int)entry.ProcessId) && (entry.Exe.Equals("codex.exe", StringComparison.OrdinalIgnoreCase) || entry.Exe.Equals("ChatGPT.exe", StringComparison.OrdinalIgnoreCase))) return true;
                next = Process32Next(snapshot, ref entry);
            }
            return false;
        } finally { CloseHandle(snapshot); }
    }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct ProcessEntry { public uint Size, Usage, ProcessId; public UIntPtr Heap; public uint Module, Threads, Parent; public int Priority; public uint Flags; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string Exe; }
    [DllImport("kernel32.dll")] private static extern IntPtr CreateToolhelp32Snapshot(uint flags, uint pid);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, EntryPoint = "Process32FirstW")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool Process32First(IntPtr snapshot, ref ProcessEntry entry);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, EntryPoint = "Process32NextW")] [return: MarshalAs(UnmanagedType.Bool)] private static extern bool Process32Next(IntPtr snapshot, ref ProcessEntry entry);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
}
