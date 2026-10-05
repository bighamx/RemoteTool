using System.Text.Json.Nodes;
using ChuckieHelper.WebApi.Services.Codex;

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
} finally { Directory.Delete(temp, true); }
Console.WriteLine($"Agent contract checks: {count} passed");
var input = "{\"type\":\"text\",\"text\":\"" + new string('汉', 2048) + "\"}";
using var ws = new FragmentSocket(System.Text.Encoding.UTF8.GetBytes(input));
Check(await ChuckieHelper.WebApi.Services.RemoteControl.WebSocketTextReader.Read(ws, new byte[4096], default) == input, "fragmented Chinese remote text");
using var oversized = new FragmentSocket(new byte[17000]);
Check(await ChuckieHelper.WebApi.Services.RemoteControl.WebSocketTextReader.Read(oversized, new byte[4096], default) == null && oversized.ClosedAs == System.Net.WebSockets.WebSocketCloseStatus.MessageTooBig, "oversized message bounded");
Console.WriteLine($"Including remote WebSocket checks: {count} passed");
