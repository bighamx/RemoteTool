using System.Text.Json.Nodes;
using ChuckieHelper.WebApi.Services.Codex;

if (args.Length == 3 && args[0] == "--probe") {
    var watch = System.Diagnostics.Stopwatch.StartNew();
    var result = CodexRolloutSnapshot.Read(args[1], args[2]); var first = watch.Elapsed.TotalMilliseconds;
    watch.Restart(); for (var i = 0; i < 100; i++) CodexRolloutSnapshot.Read(args[1], args[2]);
    Console.WriteLine($"Rollout read: {new FileInfo(args[2]).Length / 1024 / 1024} MiB; initial {first:F1} ms; cached average {watch.Elapsed.TotalMilliseconds / 100:F3} ms; context available {result["context"]?["available"]}");
    return;
}

var catalog = JsonNode.Parse("""{"data":[{"model":"m","supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"low"}],"serviceTiers":[{"id":"priority"}],"defaultReasoningEffort":"low","defaultServiceTier":"priority"}]}""")!.AsObject();
var count = 0;
void Check(bool valid, string name) { if (!valid) throw new Exception(name); count++; }
var selection = CodexModelSettings.Validate(JsonNode.Parse("""{"model":"m","provider":"custom","reasoning_effort":"high","service_tier":"priority"}""")!.AsObject(), catalog);
var resume = new JsonObject(); CodexModelSettings.ApplyResume(resume, selection);
Check(resume["config"]?["model_reasoning_effort"]?.ToString() == "high", "resume effort");
Check(resume["serviceTier"]?.ToString() == "priority", "resume native tier");
var turn = new JsonObject(); CodexModelSettings.ApplyTurn(turn, selection, catalog["data"]![0]!.AsObject());
Check(turn["effort"]?.ToString() == "high", "turn effort");
Check(!turn.ContainsKey("reasoningEffort") && !turn.ContainsKey("modelProvider"), "turn protocol fields");
var mode = JsonNode.Parse("""{"mode":"plan","settings":{"model":"old","reasoning_effort":"medium","developer_instructions":null}}""")!.AsObject();
CodexModelSettings.ApplyTurn(turn, selection, catalog["data"]![0]!.AsObject(), mode);
Check(turn["collaborationMode"]?["mode"]?.ToString() == "plan" && turn["collaborationMode"]?["settings"]?["model"]?.ToString() == "m" && turn["collaborationMode"]?["settings"]?["reasoning_effort"]?.ToString() == "high", "desktop collaboration mode honors selected model/effort");
Check(mode["settings"]?["model"]?.ToString() == "old", "does not mutate inherited mode");
var reset = CodexModelSettings.Validate(JsonNode.Parse("""{"model":"m","provider":"custom","reasoning_effort":"","service_tier":""}""")!.AsObject(), catalog);
CodexModelSettings.ApplyTurn(turn, reset, catalog["data"]![0]!.AsObject());
Check(turn["effort"]?.ToString() == "low" && turn["serviceTier"]?.ToString() == "priority", "reset resolves catalog defaults");
var standard = CodexModelSettings.Validate(JsonNode.Parse("""{"model":"m","provider":"custom","service_tier":"standard"}""")!.AsObject(), catalog);
Check(standard["serviceTier"]?.ToString() == "default", "standard explicitly disables fast");
foreach (var bad in new[] { """{"model":"m","provider":"custom","reasoning_effort":"ultra"}""", """{"model":"m","provider":"custom","service_tier":"ultrafast"}""", """{"model":"","provider":"custom"}""" }) {
    try { CodexModelSettings.Validate(JsonNode.Parse(bad)!.AsObject(), catalog); throw new Exception("invalid setting accepted"); }
    catch (CodexError) { count++; }
}
var temp = Path.Combine(Path.GetTempPath(), "chuckie-model-settings-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(temp);
try {
    var path = Path.Combine(temp, "fixture.jsonl");
    File.WriteAllText(path, "{\"type\":\"turn_context\",\"payload\":{\"effort\":\"high\",\"service_tier\":\"priority\"}}\n{\"type\":\"turn_context\",\"payload\":{\"effort\":\"low\",\"service_tier\":\"default\"}}\n{unfinished");
    var actual = CodexRollout.LastSettings(temp, path);
    Check(actual["reasoningEffort"]?.ToString() == "low" && actual["serviceTier"]?.ToString() == "default", "latest settings and partial line");
    Check(CodexRollout.LastSettings(temp, Path.Combine(Path.GetTempPath(), "outside.jsonl")).Count == 0, "rollout path boundary");
    var history = Path.Combine(temp, "history.jsonl");
    File.WriteAllText(history, "{\"type\":\"turn_context\",\"payload\":{\"model\":\"old\",\"effort\":\"high\"}}\n");
    Check(CodexRollout.LastModel(temp, history) == "old", "snapshot initial model");
    File.AppendAllText(history, "{\"type\":\"turn_context\",\"payload\":{\"model\":\"new\"");
    Check(CodexRollout.LastModel(temp, history) == "old", "incomplete append deferred");
    File.AppendAllText(history, "}}\n{\"type\":\"event_msg\",\"payload\":{\"type\":\"token_count\",\"info\":{\"last_token_usage\":{\"total_tokens\":123},\"model_context_window\":456}}}\n");
    Check(CodexRollout.LastModel(temp, history) == "new" && CodexSessionDetails.Read(temp, history)["context"]?["tokens"]?.ToString() == "123", "completed append updates metadata");
    var question = "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call\",\"name\":\"request_user_input_async\",\"call_id\":\"q\",\"arguments\":\"{\\\"questions\\\":[{\\\"question\\\":\\\"Choose\\\"}]}\"}}\n";
    File.AppendAllText(history, question);
    Check(CodexSessionDetails.Read(temp, history)["question"] == null, "unaccepted question hidden");
    File.AppendAllText(history, "{\"type\":\"response_item\",\"payload\":{\"type\":\"function_call_output\",\"call_id\":\"q\",\"output\":\"{\\\"accepted\\\":true}\"}}\n");
    Check(CodexSessionDetails.Read(temp, history)["question"]?["request_id"]?.ToString() == "q", "accepted question visible");
    File.AppendAllText(history, "{\"type\":\"response_item\",\"payload\":{\"type\":\"message\",\"role\":\"user\"}}\n");
    Check(CodexSessionDetails.Read(temp, history)["question"] == null, "user reply clears pending question");
    File.WriteAllText(history, "{\"type\":\"turn_context\",\"payload\":{\"model\":\"reset\"}}\n");
    Check(CodexRollout.LastModel(temp, history) == "reset", "truncation resets cached metadata");
    var activityPath = Path.Combine(temp, "activity.jsonl");
    const long stamp = 1791207000000;
    string Record(string kind, JsonObject payload, long at) => System.Text.Json.JsonSerializer.Serialize(new {
        type = kind, timestamp = DateTimeOffset.FromUnixTimeMilliseconds(at).ToString("O"), payload,
    }) + "\n";
    JsonObject Event(string kind, string id = "turn-1") => new() { ["type"] = kind, ["turn_id"] = id };
    File.WriteAllText(activityPath, Record("event_msg", Event("task_started"), stamp));
    var snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["running"].GetValue<bool>() && snapshot["activity"]["started_at"].GetValue<long>() == stamp, "desktop start event gives original task start");
    Check(snapshot["activity"]["last_response_at"] == null, "no desktop response time invented");
    File.AppendAllText(activityPath, Record("response_item", new() { ["type"] = "function_call", ["name"] = "functions.exec_command", ["call_id"] = "a", ["arguments"] = "{\"cmd\":\"echo hello\"}" }, stamp+1000)
        + Record("response_item", new() { ["type"] = "function_call", ["name"] = "functions.exec_command", ["call_id"] = "b", ["arguments"] = "{}" }, stamp+2000));
    snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["activity"]["event_count"].GetValue<int>() == 2 && snapshot["activity"]["progress"].AsArray().Count == 2, "desktop same-name tools retain distinct IDs");
    Check(snapshot["activity"]["progress"][0]["preview"].ToString() == "echo hello", "desktop command preview extracted");
    File.AppendAllText(activityPath, Record("response_item", new() { ["type"] = "function_call_output", ["call_id"] = "a", ["output"] = "{\"isError\":true}" }, stamp+3000));
    snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["activity"]["progress"][0]["status"].ToString() == "failed" && snapshot["activity"]["progress"][1]["status"].ToString() == "running", "desktop output pairs by call ID");
    Check(snapshot["activity"]["last_response_at"].GetValue<long>() == stamp+3000, "tool output advances desktop response clock");
    File.AppendAllText(activityPath, Record("response_item", new() { ["type"] = "custom_tool_call", ["name"] = "exec", ["call_id"] = "freeform", ["input"] = new string('x', 400) }, stamp+4000)
        + Record("response_item", new() { ["type"] = "custom_tool_call_output", ["call_id"] = "freeform", ["output"] = "plain result" }, stamp+5000));
    snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["activity"]["progress"][2]["status"].ToString() == "completed" && snapshot["activity"]["progress"][2]["preview"].ToString().Length <= 161, "freeform desktop tools supported with bounded preview");
    File.AppendAllText(activityPath, Record("response_item", new() { ["type"] = "reasoning", ["text"] = "THOUGHT_DO_NOT_EXPOSE" }, stamp+6000));
    snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["activity"]["last_response_at"].GetValue<long>() == stamp+6000 && !snapshot["activity"].ToJsonString().Contains("THOUGHT_DO_NOT_EXPOSE"), "activity clock observes reasoning without exposing content");
    File.AppendAllText(activityPath, Record("event_msg", Event("task_complete", "older-turn"), stamp+7000));
    Check(CodexRolloutSnapshot.Read(temp, activityPath)["running"].GetValue<bool>(), "old completion cannot end newer desktop task");
    File.AppendAllText(activityPath, Record("event_msg", Event("task_complete"), stamp+8000));
    Check(!CodexRolloutSnapshot.Read(temp, activityPath)["running"].GetValue<bool>(), "matching completion clears desktop running state");
    File.AppendAllText(activityPath, Record("event_msg", Event("task_started", "turn-2"), stamp+9000));
    snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["activity"]["event_count"].GetValue<int>() == 0 && snapshot["activity"]["last_response_at"] == null, "new desktop task resets progress and response time");
    using (var writer = new FileStream(activityPath, FileMode.Open, FileAccess.Write, FileShare.ReadWrite)) {
        Check(CodexRollout.IsRunning(temp, activityPath), "live writer plus start event proves desktop running on Windows");
    }
    Check(!CodexRollout.IsRunning(temp, activityPath), "orphan start without a live writer is not a running task");
    for (var i = 0; i < 40; i++) File.AppendAllText(activityPath, Record("response_item", new() { ["type"] = "function_call", ["name"] = "test", ["call_id"] = "many-"+i, ["arguments"] = "{}" }, stamp+10000+i));
    snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["activity"]["event_count"].GetValue<int>() == 40 && snapshot["activity"]["progress"].AsArray().Count == 30, "desktop tool total survives display limit");
} finally { Directory.Delete(temp, true); }
Console.WriteLine($"Agent contract checks: {count} passed");
var input = "{\"type\":\"text\",\"text\":\"" + new string('汉', 2048) + "\"}";
using var ws = new FragmentSocket(System.Text.Encoding.UTF8.GetBytes(input));
Check(await ChuckieHelper.WebApi.Services.RemoteControl.WebSocketTextReader.Read(ws, new byte[4096], default) == input, "fragmented Chinese remote text");
using var oversized = new FragmentSocket(new byte[17000]);
Check(await ChuckieHelper.WebApi.Services.RemoteControl.WebSocketTextReader.Read(oversized, new byte[4096], default) == null && oversized.ClosedAs == System.Net.WebSockets.WebSocketCloseStatus.MessageTooBig, "oversized message bounded");
Console.WriteLine($"Including remote WebSocket checks: {count} passed");
