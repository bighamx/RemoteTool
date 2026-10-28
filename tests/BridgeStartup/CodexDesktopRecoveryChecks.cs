using System.Reflection;
using System.Text.Json.Nodes;

internal static class CodexDesktopRecoveryChecks
{
    public static async Task Run(Assembly assembly) {
        if (!OperatingSystem.IsMacOS()) return;
        var root = Path.Combine("/tmp", "rc-observer-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var config = Path.Combine(root, "connection.json");
        File.WriteAllText(config, new JsonObject { ["home"] = root, ["state_folder"] = root, ["account_store"] = Path.Combine(root, "accounts") }.ToJsonString());
        var type = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAgent", true)!;
        var agent = (IAsyncDisposable)type.GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null, [typeof(string)], null)!.Invoke([config]);
        object Field(string name) => type.GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(agent)!;
        var runs = (JsonObject)Field("runs");
        var active = (Dictionary<string, string>)Field("active");
        async Task Observe() => await (Task)type.GetMethod("ObserveDesktopRun", BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(agent, ["session", "run", "key"] )!;
        try {
            runs["run"] = new JsonObject { ["session_id"] = "session", ["status"] = "started", ["owner"] = "desktop", ["approval"] = new JsonObject() };
            active["session"] = "run";
            await Observe().WaitAsync(TimeSpan.FromSeconds(5));
            if (runs["run"]!["status"]!.ToString() != "acceptance_unknown" || runs["run"]!["error_code"]!.ToString() != "run_tracking_lost" ||
                runs["run"]!.AsObject().ContainsKey("approval") || active.ContainsKey("session"))
                throw new Exception("A disconnected observer must clear busy/approval state and permit recovery");
            runs["run"]!["status"] = "completed";
            active["session"] = "new-run";
            await Observe().WaitAsync(TimeSpan.FromSeconds(5));
            if (runs["run"]!["status"]!.ToString() != "completed" || active["session"] != "new-run")
                throw new Exception("An old observer must preserve a terminal result and a newer task");
            Console.WriteLine("PASS: disconnected desktop observer clears stale busy state and preserves newer results");
        } finally { await agent.DisposeAsync(); Directory.Delete(root, true); }
    }
}
