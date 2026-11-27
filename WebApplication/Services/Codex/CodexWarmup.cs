using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal sealed record WarmupWorkspace(string Key, string AccountId, string WorkspaceId, string Name);

internal interface ICodexWarmupRuntime
{
    IReadOnlyList<WarmupWorkspace> Workspaces();
    Task<JsonObject> Usage(WarmupWorkspace workspace, CancellationToken ct);
    Task<JsonObject> Send(WarmupWorkspace workspace, JsonObject attempt, Action<JsonObject> checkpoint, CancellationToken ct);
    Task<JsonObject> Reconcile(WarmupWorkspace workspace, JsonObject attempt, CancellationToken ct);
}

internal sealed class CodexWarmup : IDisposable
{
    internal const long WindowSeconds = 5 * 60 * 60;
    internal const long IntervalSeconds = 10 * 60;
    private readonly string root, path;
    private readonly Mutex mutex;
    private readonly ICodexWarmupRuntime runtime;
    private readonly Func<long> clock;

    public CodexWarmup(string folder, ICodexWarmupRuntime runtime, Func<long> clock = null)
    {
        root = Path.GetFullPath(Path.Combine(folder, "workspace-warmup"));
        path = Path.Combine(root, "state.json");
        this.runtime = runtime;
        this.clock = clock ?? (() => DateTimeOffset.UtcNow.ToUnixTimeSeconds());
        mutex = new Mutex(false, "Local\\RemoteToolCodexWarmup-" + Hash(OperatingSystem.IsWindows() ? path.ToUpperInvariant() : path)[..20]);
    }

    internal static JsonObject ReadState(string path)
    {
        if (!File.Exists(path)) return new JsonObject();
        using var stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete);
        using var reader = new StreamReader(stream);
        return JsonNode.Parse(reader.ReadToEnd())!.AsObject();
    }

    internal static bool NeedsScan(string folder)
    {
        var state = ReadState(Path.Combine(folder, "workspace-warmup", "state.json"));
        return state.B("enabled") || state["workspaces"] is JsonObject rows && rows.Any(row => Pending(row.Value));
    }

    private T Access<T>(Func<JsonObject, T> action, bool write = false)
    {
        try { if (!mutex.WaitOne(TimeSpan.FromSeconds(5))) throw new CodexError("热身设置正在保存，请稍后重试", 409); }
        catch (AbandonedMutexException) { }
        try
        {
            var state = ReadState(path);
            state["workspaces"] ??= new JsonObject();
            var result = action(state);
            if (write)
            {
                IdentityDataDirectory.Ensure(root);
                Atomic(path, state);
            }
            return result;
        }
        finally { mutex.ReleaseMutex(); }
    }

    public JsonObject Status() => Access(state => Obj(
        ("supported", true), ("enabled", state.B("enabled")), ("interval_minutes", 10),
        ("last_check_at", state.L("last_check_at")), ("next_check_at", state.L("last_check_at") > 0 ? state.L("last_check_at") + IntervalSeconds : 0),
        ("checking", state.B("checking") && ScanLocked()), ("error", state.S("error")),
        ("items", new JsonArray(state["workspaces"]!.AsObject().Select(row => (JsonNode)Obj(
            ("account_id", row.Value.S("account_id")), ("workspace_id", row.Value.S("workspace_id")),
            ("status", row.Value.S("status")), ("message", row.Value.S("message")),
            ("last_checked_at", row.Value.L("last_checked_at")), ("last_warmup_at", row.Value.L("last_warmup_at")),
            ("next_reset_at", row.Value.L("next_reset_at")), ("model", row.Value.S("model")))) .ToArray()))));

    public JsonObject SetEnabled(bool enabled)
    {
        Access(state => { state["enabled"] = enabled; state["error"] = ""; return true; }, write: true);
        return Status();
    }

    private bool ScanLocked()
    {
        if (!Directory.Exists(root)) return false;
        try { using var file = new FileStream(Path.Combine(root, "scan.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); return false; }
        catch (IOException) { return true; }
    }

    internal static bool Pending(JsonNode row) => row.S("status") is "preparing" or "sending" or "pending_verification";
    public bool BlocksMaintenance => Access(state => state["workspaces"]!.AsObject().Any(row => Pending(row.Value)));

    internal static JsonNode FiveHourWindow(JsonObject usage) =>
        new[] { usage["rateLimits"]?["primary"], usage["rateLimits"]?["secondary"] }
            .FirstOrDefault(window => window.L("windowDurationMins") == 300);

    internal static bool Eligible(JsonObject usage, long observedAt)
    {
        var window = FiveHourWindow(usage);
        if (window == null || !double.TryParse(window.S("usedPercent"), System.Globalization.NumberStyles.Float,
            System.Globalization.CultureInfo.InvariantCulture, out var used) || used != 0) return false;
        var serverTime = usage.L("observed_at");
        if (serverTime <= 0) serverTime = observedAt;
        var remaining = window.L("resetsAt") - serverTime;
        var weekly = new[] { usage["rateLimits"]?["primary"], usage["rateLimits"]?["secondary"] }
            .FirstOrDefault(value => value.L("windowDurationMins") == 10080);
        return Math.Abs(remaining - WindowSeconds) <= 5 && (weekly == null || weekly.L("usedPercent") < 100);
    }

    public async Task Scan(CancellationToken ct)
    {
        if (!NeedsScan(Path.GetDirectoryName(root)!)) return;
        IdentityDataDirectory.Ensure(root);
        FileStream scanLock;
        try { scanLock = new FileStream(Path.Combine(root, "scan.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException) { return; }
        using var held = scanLock;
        var due = Access(state => {
            if (state.L("last_check_at") > clock() - IntervalSeconds) return false;
            state["last_check_at"] = clock(); state["checking"] = true; state["error"] = "";
            return true;
        }, write: true);
        if (!due) return;
        try
        {
            var pending = Access(state => state["workspaces"]!.AsObject().Where(row => Pending(row.Value))
                .Select(row => new WarmupWorkspace(row.Key, row.Value.S("account_id"), row.Value.S("workspace_id"), "")).ToArray());
            var workspaces = runtime.Workspaces().Concat(pending).DistinctBy(workspace => workspace.Key).ToArray();
            foreach (var workspace in workspaces)
            {
                ct.ThrowIfCancellationRequested();
                var previous = Access(state => state["workspaces"]?[workspace.Key]?.DeepClone() as JsonObject);
                if (Pending(previous))
                {
                    if (previous.S("status") == "preparing")
                        Save(workspace, previous.S("request_key"), Obj(("status", "failed"), ("message", "准备过程已中断，将在下次检查时重新准备")));
                    else
                    {
                        try { await Finish(workspace, previous, await runtime.Reconcile(workspace, previous, ct), ct); }
                        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                        catch (Exception) { Save(workspace, previous.S("request_key"), Obj(("status", "pending_verification"), ("message", "正在核对原热身请求，等待明确结果"))); }
                        continue;
                    }
                }
                if (!Access(state => state.B("enabled"))) continue;
                if (previous.L("hold_until") > clock()) continue;
                try
                {
                    var usage = await runtime.Usage(workspace, ct);
                    var reset = FiveHourWindow(usage).L("resetsAt");
                    if (!Eligible(usage, clock()))
                    {
                        Save(workspace, null, Obj(("last_checked_at", clock()), ("next_reset_at", reset),
                            ("status", "skipped"), ("message", "当前额度窗口无需热身")));
                        continue;
                    }
                    var attempt = Access(state => {
                        if (!state.B("enabled")) return null;
                        var rows = state["workspaces"]!.AsObject();
                        if (Pending(rows[workspace.Key]) || rows[workspace.Key].L("hold_until") > clock()) return null;
                        var row = Obj(("account_id", workspace.AccountId), ("workspace_id", workspace.WorkspaceId),
                            ("request_key", "warmup-" + Guid.NewGuid().ToString("N")), ("status", "preparing"),
                            ("last_checked_at", clock()), ("last_warmup_at", rows[workspace.Key].L("last_warmup_at")),
                            ("next_reset_at", reset), ("message", "正在准备最小热身请求"));
                        rows[workspace.Key] = row;
                        return row.DeepClone().AsObject();
                    }, write: true);
                    if (attempt == null) continue;
                    try
                    {
                        var receipt = await runtime.Send(workspace, attempt, patch => {
                            Save(workspace, attempt.S("request_key"), patch, requireEnabled: patch.S("status") == "sending");
                            foreach (var field in patch) attempt[field.Key] = field.Value?.DeepClone();
                        }, ct);
                        await Finish(workspace, attempt, receipt, ct);
                    }
                    catch (Exception error) when (error is not OperationCanceledException || !ct.IsCancellationRequested)
                    {
                        var rejected = attempt.S("status") != "sending";
                        Save(workspace, attempt.S("request_key"), Obj(("status", rejected ? "failed" : "pending_verification"),
                            ("message", rejected ? "热身请求未执行，将在下次检查时重试" : "发送结果待确认，正在保留原请求核对")));
                    }
                }
                catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
                catch (Exception) { Save(workspace, null, Obj(("status", "failed"), ("last_checked_at", clock()), ("message", "暂时无法读取此工作空间额度或登录，请刷新检查"))); }
            }
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception) { Access(state => { state["error"] = "热身检查暂未完成，请刷新后重试"; return true; }, write: true); }
        finally { Access(state => { state["checking"] = false; return true; }, write: true); }
    }

    private void Save(WarmupWorkspace workspace, string request, JsonObject patch, bool requireEnabled = false)
    {
        Access(state => {
            if (requireEnabled && !state.B("enabled")) throw new CodexError("自动热身已关闭", 409);
            var rows = state["workspaces"]!.AsObject();
            var row = rows[workspace.Key] as JsonObject;
            if (request != null && row.S("request_key") != request) throw new CodexError("热身请求状态已变化", 409);
            row ??= Obj(("account_id", workspace.AccountId), ("workspace_id", workspace.WorkspaceId));
            foreach (var field in patch) row[field.Key] = field.Value?.DeepClone();
            rows[workspace.Key] = row;
            return true;
        }, write: true);
    }

    private async Task Finish(WarmupWorkspace workspace, JsonObject attempt, JsonObject receipt, CancellationToken ct)
    {
        var status = receipt.S("status");
        if (status is not ("completed" or "failed" or "interrupted"))
        {
            Save(workspace, attempt.S("request_key"), Obj(("status", "pending_verification"), ("message", "正在核对原热身请求，等待明确结果")));
            return;
        }
        var started = attempt.L("dispatched_at");
        if (started <= 0) started = clock();
        Save(workspace, attempt.S("request_key"), Obj(("status", status == "completed" ? "completed" : "failed"),
            ("last_warmup_at", status == "completed" ? started : attempt.L("last_warmup_at")),
            ("hold_until", started + WindowSeconds), ("message", status == "completed" ? "已热身" : "热身执行未完成，已保留本轮记录")));
        try
        {
            var usage = await runtime.Usage(workspace, ct);
            Save(workspace, attempt.S("request_key"), Obj(("next_reset_at", FiveHourWindow(usage).L("resetsAt"))));
        }
        catch (OperationCanceledException) when (ct.IsCancellationRequested) { throw; }
        catch (Exception) { /* The accepted native turn remains confirmed even if the follow-up quota read fails. */ }
    }

    public void Dispose() => mutex.Dispose();
}
