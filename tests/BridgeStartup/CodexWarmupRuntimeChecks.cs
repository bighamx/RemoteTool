using System.Collections;
using System.Reflection;
using System.Text;
using System.Text.Json.Nodes;

internal static class CodexWarmupRuntimeChecks
{
    public static async Task FakeCli(string[] args)
    {
        var home = Environment.GetEnvironmentVariable("CODEX_HOME")!;
        if (Directory.GetCurrentDirectory() != home || !args.Contains("model_provider=\"openai\""))
            throw new Exception("The native warmup must use an isolated working directory and official provider");
        while (await Console.In.ReadLineAsync() is { } line)
        {
            var request = JsonNode.Parse(line)!;
            if (request["id"] == null) continue;
            var method = request["method"]!.ToString();
            await File.AppendAllTextAsync(Path.Combine(home, "calls.jsonl"), request.ToJsonString() + "\n");
            JsonObject result = method switch {
                "account/read" => new() { ["account"] = new JsonObject { ["type"] = "chatgpt" } },
                "model/list" => new() { ["data"] = new JsonArray(new JsonObject {
                    ["model"] = "fixture-official", ["isDefault"] = true,
                    ["supportedReasoningEfforts"] = new JsonArray(new JsonObject { ["reasoningEffort"] = "low" })
                }) },
                "thread/start" => new() { ["thread"] = new JsonObject { ["id"] = "warm-thread" } },
                "turn/start" => new() { ["turn"] = new JsonObject { ["id"] = "warm-turn", ["status"] = "inProgress" } },
                "thread/turns/list" => new() { ["data"] = new JsonArray(new JsonObject { ["id"] = "warm-turn", ["status"] = "completed" }) },
                _ => new()
            };
            await Console.Out.WriteLineAsync(new JsonObject { ["id"] = request["id"]!.DeepClone(), ["result"] = result }.ToJsonString());
            if (method == "turn/start")
                await Console.Out.WriteLineAsync(new JsonObject {
                    ["method"] = "turn/completed", ["params"] = new JsonObject {
                        ["threadId"] = "warm-thread", ["turn"] = new JsonObject { ["id"] = "warm-turn", ["status"] = "completed" }
                    }
                }.ToJsonString());
            await Console.Out.FlushAsync();
        }
    }

    private static JsonObject Auth(string workspace, string email)
    {
        var payload = new JsonObject {
            ["sub"] = email, ["email"] = email,
            ["https://api.openai.com/auth"] = new JsonObject { ["chatgpt_account_id"] = workspace, ["chatgpt_plan_type"] = "plus" }
        };
        var jwt = "fixture." + Convert.ToBase64String(Encoding.UTF8.GetBytes(payload.ToJsonString())).TrimEnd('=').Replace('+', '-').Replace('/', '_') + ".signature";
        return new JsonObject { ["tokens"] = new JsonObject { ["account_id"] = workspace, ["id_token"] = jwt, ["access_token"] = jwt, ["refresh_token"] = "fixture-refresh" } };
    }

    public static async Task Run(Assembly assembly)
    {
        var root = Path.Combine(Path.GetTempPath(), "codex-warmup-sdk-" + Guid.NewGuid().ToString("N"));
        var current = Path.Combine(root, "current-home");
        var folder = Path.Combine(root, "bridge-state");
        var cli = Path.Combine(root, "cli");
        Directory.CreateDirectory(current); Directory.CreateDirectory(cli);
        var originalAuth = Auth("workspace-a", "a@fixture.invalid").ToJsonString();
        File.WriteAllText(Path.Combine(current, "auth.json"), originalAuth);
        File.WriteAllText(Path.Combine(current, "config.toml"), "model_provider=\"fixture-third-party\"");
        foreach (var file in Directory.GetFiles(AppContext.BaseDirectory, "BridgeStartup*")) File.Copy(file, Path.Combine(cli, Path.GetFileName(file)));
        foreach (var name in new[] { "codex-code-mode-host.exe", "codex-command-runner.exe" }) File.WriteAllText(Path.Combine(cli, name), "fixture");
        var executable = Path.Combine(cli, OperatingSystem.IsWindows() ? "BridgeStartup.exe" : "BridgeStartup");
        if (!OperatingSystem.IsWindows()) File.SetUnixFileMode(executable, UnixFileMode.UserRead | UnixFileMode.UserWrite | UnixFileMode.UserExecute);
        var oldFlag = Environment.GetEnvironmentVariable("REMOTETOOL_WARMUP_FIXTURE");
        try
        {
            Environment.SetEnvironmentVariable("REMOTETOOL_WARMUP_FIXTURE", "1");
            var storeType = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAccountStore", true)!;
            var store = Activator.CreateInstance(storeType, [current, Path.Combine(root, "account-store")])!;
            storeType.GetMethod("Capture")!.Invoke(store, [Auth("workspace-a", "a@fixture.invalid"), "A"]);
            storeType.GetMethod("Capture")!.Invoke(store, [Auth("workspace-b", "b@fixture.invalid"), "B"]);
            var runtimeType = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexWarmupRuntime", true)!;
            var runtime = Activator.CreateInstance(runtimeType, [store, executable, folder])!;
            var spaces = ((IEnumerable)runtimeType.GetMethod("Workspaces")!.Invoke(runtime, null)!).Cast<object>().ToArray();
            if (spaces.Length != 2) throw new Exception("Warmup must enumerate workspaces from every saved account");
            foreach (var workspace in spaces)
            {
                var attempt = new JsonObject { ["request_key"] = "warmup-" + Guid.NewGuid().ToString("N") };
                void Checkpoint(JsonObject patch) { foreach (var field in patch) attempt[field.Key] = field.Value?.DeepClone(); }
                var completed = await ((Task<JsonObject>)runtimeType.GetMethod("Send")!.Invoke(runtime,
                    [workspace, attempt, (Action<JsonObject>)Checkpoint, CancellationToken.None])!).WaitAsync(TimeSpan.FromSeconds(10));
                if (completed["status"]?.ToString() != "completed") throw new Exception("The native completion event was not consumed");
                var home = Path.Combine(folder, "workspace-warmup", "requests", attempt["request_key"]!.ToString());
                var calls = File.ReadAllLines(Path.Combine(home, "calls.jsonl")).Select(line => JsonNode.Parse(line)!).ToArray();
                var turn = calls.Single(row => row["method"]!.ToString() == "turn/start")["params"]!;
                if (turn["input"]!.AsArray().Count != 1 || turn["input"]![0]!["text"]?.ToString() != "只回复 OK。" ||
                    turn["effort"]?.ToString() != "low" || turn["clientUserMessageId"]?.ToString() != attempt["request_key"]!.ToString())
                    throw new Exception("Warmup must send one minimal input with its persisted identity and supported low effort");
                var selectedWorkspace = workspace.GetType().GetProperty("WorkspaceId")!.GetValue(workspace)!.ToString();
                var copiedAuth = JsonNode.Parse(File.ReadAllText(Path.Combine(home, "auth.json")))!;
                if (copiedAuth["tokens"]?["account_id"]?.ToString() != selectedWorkspace)
                    throw new Exception("A warmup used another workspace's credentials");
                var receipt = await (Task<JsonObject>)runtimeType.GetMethod("Reconcile")!.Invoke(runtime, [workspace, attempt, CancellationToken.None])!;
                if (receipt["status"]?.ToString() != "completed" ||
                    File.ReadAllLines(Path.Combine(home, "calls.jsonl")).Select(line => JsonNode.Parse(line)!).Count(row => row["method"]!.ToString() == "turn/start") != 1)
                    throw new Exception("Receipt reconciliation must not start another turn");
            }
            if (File.ReadAllText(Path.Combine(current, "auth.json")) != originalAuth ||
                File.ReadAllText(Path.Combine(current, "config.toml")) != "model_provider=\"fixture-third-party\"")
                throw new Exception("Warmup modified the active desktop account or configuration");
            Console.WriteLine("PASS native warmup: multiple saved accounts, isolated CWD/auth/provider, minimal turn, ordered completion, supported effort and read-only receipt reconciliation");
        }
        finally { Environment.SetEnvironmentVariable("REMOTETOOL_WARMUP_FIXTURE", oldFlag); Directory.Delete(root, true); }
    }
}
