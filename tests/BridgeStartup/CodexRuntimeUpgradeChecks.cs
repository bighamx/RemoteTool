using System.Reflection;
using System.Text.Json.Nodes;

internal static class CodexRuntimeUpgradeChecks
{
    public static async Task Run(Assembly assembly, string installedExe) {
        var type = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAgent", true)!;
        var ctor = type.GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null, [typeof(string)], null)!;
        var profile = Path.Combine(Path.GetTempPath(), "codex-runtime-update-" + Guid.NewGuid().ToString("N"));
        var home = Path.Combine(profile, ".codex"); Directory.CreateDirectory(home);
        var bin = Path.Combine(profile, "AppData", "Local", "OpenAI", "Codex", "bin");
        string Bundle(string name) {
            var directory = Path.Combine(bin, name); Directory.CreateDirectory(directory);
            foreach (var file in new[] { "codex.exe", "codex-code-mode-host.exe", "codex-command-runner.exe" })
                File.Copy(Path.Combine(Path.GetDirectoryName(installedExe)!, file), Path.Combine(directory, file));
            return Path.Combine(directory, "codex.exe");
        }
        var old = Bundle("old"); var next = Bundle("next");
        var config = Path.Combine(profile, "connection.json");
        File.WriteAllText(config, new JsonObject { ["home"] = home, ["executable"] = old, ["token"] = "test-only", ["state_folder"] = profile }.ToJsonString());
        var agent = (IAsyncDisposable)ctor.Invoke([config]);
        object Field(string name) => type.GetField(name, BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(agent)!;
        async Task Call(string method) => await (Task)type.GetMethod(method, BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(agent, null)!;
        object Rpc() => Field("rpc");
        int Pid() => (int)Rpc().GetType().GetProperty("ProcessId")!.GetValue(Rpc())!;
        void Check(bool value, string name) { if (!value) throw new Exception(name); Console.WriteLine("PASS: " + name); }
        try {
            await Call("Launch"); var oldPid = Pid();
            File.Delete(Path.Combine(Path.GetDirectoryName(old)!, "codex-code-mode-host.exe"));
            var active = (Dictionary<string, string>)Field("active");
            active["test-session"] = "test-run";
            await Call("EnsureConnection");
            Check(Pid() == oldPid, "tool refresh retains the process while an accepted turn is active");
            var startRun = type.GetMethod("StartRunCore", BindingFlags.NonPublic | BindingFlags.Instance)!;
            try {
                await (Task<JsonObject>)startRun.Invoke(agent, [new JsonObject { ["session_id"] = "new-test-session", ["input"] = "must not send" }, "upgrade-test-key-1234"])!;
                throw new Exception("new turn accepted with missing tools");
            } catch (Exception error) when (error.GetType().Name == "CodexError") {
                Check((string)error.GetType().GetProperty("Code")!.GetValue(error)! == "codex_update_pending" &&
                    ((JsonObject)Field("runs")).Count == 0, "new input is rejected before journaling or starting a turn during refresh");
            }
            active.Clear();
            var runs = (JsonObject)Field("runs");
            runs["queued-test"] = new JsonObject { ["native_queue"] = true, ["status"] = "queued", ["session_id"] = "unverifiable-test-session" };
            await Call("EnsureConnection");
            Check(Pid() == oldPid, "tool refresh retains queued submissions when native state cannot prove idle");
            runs.Clear();
            var newHost = Path.Combine(Path.GetDirectoryName(next)!, "codex-code-mode-host.exe");
            File.Move(newHost, newHost + ".saved");
            try { await Call("EnsureConnection"); throw new Exception("partial update accepted"); }
            catch (Exception error) when (error.GetType().Name == "CodexError" || error is TargetInvocationException { InnerException: { } inner } && inner.GetType().Name == "CodexError") { }
            Check(Pid() == oldPid && (bool)Rpc().GetType().GetProperty("Running")!.GetValue(Rpc())!, "partial update leaves the reader alive for retry");
            File.Move(newHost + ".saved", newHost);
            var rpcCall = Rpc().GetType().GetMethod("Call")!;
            var created = await (Task<JsonObject>)rpcCall.Invoke(Rpc(), ["thread/start", new JsonObject { ["cwd"] = profile }, CancellationToken.None])!;
            var sid = created["thread"]!["id"]!.ToString();
            runs["stale-queued-test"] = new JsonObject { ["native_queue"] = true, ["status"] = "queued", ["session_id"] = sid };
            await Call("EnsureConnection");
            Check(Pid() != oldPid && (string)Rpc().GetType().GetProperty("Executable")!.GetValue(Rpc())! == next,
                "idle live app-server automatically switches to the complete bundle");
            Check(JsonNode.Parse(File.ReadAllText(config))!["executable"]!.ToString() == next, "resolved bundle is persisted for later bridge restarts");
            Check(runs["stale-queued-test"]!["status"]!.ToString() == "acceptance_unknown", "empty native queue and idle thread reconcile a stale receipt without replay");
        } finally {
            await agent.DisposeAsync();
            // The native runtime's shutdown can briefly retain SQLite directory handles.
            for (var attempt = 0; ; attempt++) {
                try { Directory.Delete(profile, true); break; }
                catch (IOException) when (attempt < 50) { await Task.Delay(200); }
                catch (IOException) { Console.WriteLine("Fixture cleanup deferred until process exit: " + profile); break; }
            }
        }
    }
}
