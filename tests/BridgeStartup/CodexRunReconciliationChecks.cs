using System.Collections;
using System.Reflection;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;

internal static class CodexRunReconciliationChecks
{
    public static async Task Run(Assembly assembly) {
        var root = Path.Combine(Path.GetTempPath(), "rc-run-reconcile-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        var config = Path.Combine(root, "connection.json");
        File.WriteAllText(config, new JsonObject { ["home"] = root, ["state_folder"] = root, ["account_store"] = Path.Combine(root, "accounts") }.ToJsonString());
        var flags = BindingFlags.NonPublic | BindingFlags.Instance;
        var type = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAgent", true)!;
        var agent = (IAsyncDisposable)type.GetConstructor(flags, null, [typeof(string)], null)!.Invoke([config]);
        var runs = (JsonObject)type.GetField("runs", flags)!.GetValue(agent)!;
        var active = (Dictionary<string, string>)type.GetField("active", flags)!.GetValue(agent)!;
        var activity = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexDesktopActivity", true)!;
        var cache = (IDictionary)activity.GetField("cache", BindingFlags.NonPublic | BindingFlags.Static)!.GetValue(null)!;
        var entryType = activity.GetNestedType("Entry", BindingFlags.NonPublic)!;
        var cacheKey = Path.GetFullPath(root) + "\nsession";
        var observation = new JsonObject { ["available"] = true, ["running"] = true, ["activity_id"] = "new-turn",
            ["turn_statuses"] = new JsonObject { ["old-turn"] = "completed", ["new-turn"] = "inProgress" } };
        try {
            foreach (var endpoint in new[] { "status", "lookup", "events" }) {
                runs["old-run"] = new JsonObject { ["run_id"] = "old-run", ["session_id"] = "session", ["owner"] = "desktop",
                    ["status"] = "acceptance_unknown", ["error_code"] = "run_tracking_lost", ["error"] = "lost subscription",
                    ["turn_id"] = "old-turn", ["key"] = "fixture_request_key" };
                runs["new-run"] = new JsonObject { ["run_id"] = "new-run", ["status"] = "started" };
                active["session"] = "new-run";
                cache[cacheKey] = Activator.CreateInstance(entryType, Environment.TickCount64, Task.FromResult(observation))!;
                var context = new DefaultHttpContext(); context.Request.Method = "GET"; context.Response.Body = new MemoryStream();
                if (endpoint == "events") {
                    await (Task)type.GetMethod("Stream", flags)!.Invoke(agent, [context, "old-run"])!;
                } else {
                    context.Request.Path = endpoint == "status" ? "/runs/old-run" : "/runs/lookup";
                    if (endpoint == "lookup") context.Request.QueryString = new QueryString("?key=fixture_request_key");
                    var response = await (Task<JsonObject>)type.GetMethod("Route", flags)!.Invoke(agent, [context, new JsonObject()])!;
                    if (response["status"]?.ToString() != "completed") throw new Exception(endpoint + " exposed a stale unknown status");
                }
                if (runs["old-run"]!["status"]?.ToString() != "completed" || runs["old-run"]!["error"]?.ToString() != "" || active["session"] != "new-run")
                    throw new Exception(endpoint + " must reconcile only the old exact turn and preserve newer work");
            }
            Console.WriteLine("PASS: status, request lookup and SSE reconcile an exact desktop result after restart");
        } finally { cache.Remove(cacheKey); await agent.DisposeAsync(); Directory.Delete(root, true); }
    }
}
