using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

internal static class CodexWarmupChecks
{
    private sealed class Runtime : ICodexWarmupRuntime
    {
        public long Now = 1_800_000_000;
        public int Sends, Reads, Reconciliations;
        public IReadOnlyList<WarmupWorkspace> Spaces = [new("a", "account-a", "workspace-a", "A")];
        public Func<WarmupWorkspace, JsonObject> Read;
        public Func<JsonObject, CancellationToken, Task<JsonObject>> Dispatch;
        public JsonObject Receipt = Obj(("status", "completed"));
        public IReadOnlyList<WarmupWorkspace> Workspaces() => Spaces;
        public Task<JsonObject> Usage(WarmupWorkspace workspace, CancellationToken ct)
        {
            Reads++;
            return Task.FromResult(Read?.Invoke(workspace) ?? Quota(Now));
        }
        public Task<JsonObject> Send(WarmupWorkspace workspace, JsonObject attempt, Action<JsonObject> checkpoint, CancellationToken ct)
        {
            Sends++;
            checkpoint(Obj(("thread_id", "thread"), ("status", "sending"), ("dispatched_at", Now), ("model", "fixture-model")));
            return Dispatch?.Invoke(attempt, ct) ?? Task.FromResult(Receipt.DeepClone().AsObject());
        }
        public Task<JsonObject> Reconcile(WarmupWorkspace workspace, JsonObject attempt, CancellationToken ct)
        {
            Reconciliations++;
            return Task.FromResult(Receipt.DeepClone().AsObject());
        }
    }

    private static JsonObject Quota(long now, double used = 0, long delta = 18000, long serverTime = 0) =>
        Obj(("observed_at", serverTime), ("rateLimits", Obj(("primary", Obj(("usedPercent", used),
            ("windowDurationMins", 300), ("resetsAt", now + delta))), ("secondary", Obj(("usedPercent", 20), ("windowDurationMins", 10080))))));

    public static async Task Run()
    {
        var root = Path.Combine(Path.GetTempPath(), "codex-warmup-checks-" + Guid.NewGuid().ToString("N"));
        try
        {
            var runtime = new Runtime();
            var path = Path.Combine(root, "normal");
            using (var warmup = new CodexWarmup(path, runtime, () => runtime.Now))
            {
                if (warmup.Status().B("enabled")) throw new Exception("Warmup must default off");
                await warmup.Scan(CancellationToken.None);
                if (runtime.Sends != 0 || runtime.Reads != 0) throw new Exception("Disabled warmup must not access a workspace");
                warmup.SetEnabled(true);
                await warmup.Scan(CancellationToken.None);
                if (runtime.Sends != 1 || warmup.Status().A("items")[0].S("status") != "completed") throw new Exception("An eligible workspace must warm once");
                await warmup.Scan(CancellationToken.None);
                runtime.Now += 600;
                await warmup.Scan(CancellationToken.None);
                if (runtime.Sends != 1) throw new Exception("A rolling reset time and 100% remaining must not trigger another warmup");
            }
            using (var restarted = new CodexWarmup(path, runtime, () => runtime.Now))
            {
                runtime.Now += 600;
                await restarted.Scan(CancellationToken.None);
                if (runtime.Sends != 1) throw new Exception("Restart must preserve the whole-window duplicate guard");
                runtime.Now += 18000;
                await restarted.Scan(CancellationToken.None);
                if (runtime.Sends != 2) throw new Exception("The next full window must become eligible again");
            }
            if (!CodexWarmup.Eligible(Quota(1_800_000_000, delta: 18004), 1_800_000_000) ||
                CodexWarmup.Eligible(Quota(1_800_000_000, delta: 17900), 1_800_000_000) ||
                CodexWarmup.Eligible(Quota(1_800_000_000, used: 0.01), 1_800_000_000) ||
                !CodexWarmup.Eligible(Quota(1_800_001_000, serverTime: 1_800_001_000), 1_800_000_000))
                throw new Exception("Fresh quota eligibility must use raw usage, server time and bounded seconds tolerance");

            var accounts = new Runtime { Spaces = [new("a", "account-a", "workspace-a", "A"), new("a", "alias-a", "workspace-a", "A"), new("b", "account-b", "workspace-b", "B")] };
            using (var warmup = new CodexWarmup(Path.Combine(root, "accounts"), accounts, () => accounts.Now))
            {
                warmup.SetEnabled(true);
                await warmup.Scan(CancellationToken.None);
                if (accounts.Sends != 2) throw new Exception("Scan must cover multiple accounts and deduplicate workspace aliases");
            }

            var unknown = new Runtime { Dispatch = (_, _) => throw new IOException("response lost"), Receipt = new JsonObject() };
            var uncertainPath = Path.Combine(root, "uncertain");
            using (var warmup = new CodexWarmup(uncertainPath, unknown, () => unknown.Now))
            {
                warmup.SetEnabled(true);
                await warmup.Scan(CancellationToken.None);
                if (!warmup.BlocksMaintenance || warmup.Status().A("items")[0].S("status") != "pending_verification") throw new Exception("A lost send acknowledgement must remain uncertain");
                warmup.SetEnabled(false);
            }
            unknown.Now += 600;
            unknown.Spaces = []; // Removing a saved login must still allow local receipt reconciliation.
            using (var warmup = new CodexWarmup(uncertainPath, unknown, () => unknown.Now))
            {
                await warmup.Scan(CancellationToken.None);
                if (unknown.Sends != 1 || unknown.Reconciliations != 1) throw new Exception("Uncertain requests must only be read back, including while disabled");
                unknown.Now += 600;
                unknown.Receipt = Obj(("status", "completed"));
                await warmup.Scan(CancellationToken.None);
                if (unknown.Sends != 1 || warmup.BlocksMaintenance) throw new Exception("A confirmed original receipt must clear uncertainty without resending");
            }

            var blocked = new TaskCompletionSource<JsonObject>(TaskCreationOptions.RunContinuationsAsynchronously);
            var rpcError = new Runtime { Dispatch = (_, _) => throw new CodexError("native RPC error", 409), Receipt = new JsonObject() };
            using (var warmup = new CodexWarmup(Path.Combine(root, "rpc-error"), rpcError, () => rpcError.Now))
            {
                warmup.SetEnabled(true);
                await warmup.Scan(CancellationToken.None);
                rpcError.Now += 600;
                await warmup.Scan(CancellationToken.None);
                if (rpcError.Sends != 1 || !warmup.BlocksMaintenance)
                    throw new Exception("An RPC failure after dispatch must not automatically replay the input");
            }
            var concurrent = new Runtime { Dispatch = (_, _) => blocked.Task };
            var concurrentPath = Path.Combine(root, "concurrent");
            using (var one = new CodexWarmup(concurrentPath, concurrent, () => concurrent.Now))
            using (var two = new CodexWarmup(concurrentPath, concurrent, () => concurrent.Now))
            {
                one.SetEnabled(true);
                var active = one.Scan(CancellationToken.None);
                concurrent.Now += 600;
                await two.Scan(CancellationToken.None);
                if (concurrent.Sends != 1 || !two.Status().B("checking")) throw new Exception("Concurrent bridge instances must share a scan claim");
                two.SetEnabled(false);
                blocked.SetResult(Obj(("status", "completed")));
                await active;
                if (one.Status().B("enabled") || one.Status().A("items")[0].S("status") != "completed") throw new Exception("Disabling must retain an already sent request's outcome");
            }
            var failures = new Runtime { Spaces = [new("bad", "bad", "bad", "bad"), new("good", "good", "good", "good")] };
            failures.Read = workspace => workspace.Key == "bad" ? throw new IOException("unavailable login") : Quota(failures.Now);
            using (var warmup = new CodexWarmup(Path.Combine(root, "failures"), failures, () => failures.Now))
            {
                warmup.SetEnabled(true);
                await warmup.Scan(CancellationToken.None);
                if (failures.Sends != 1 || warmup.Status().A("items").Count != 2) throw new Exception("One failed account must not stop the remaining scan");
            }
            Console.WriteLine("PASS warmup scan: default off, full-account enumeration, server-time eligibility, per-window durable deduplication, cross-instance claims, disable/restart recovery and uncertain delivery reconciliation");
        }
        finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
    }
}
