using System.Text.Json.Nodes;
using ChuckieHelper.WebApi.Services.Codex;

if (args.Length == 3 && args[0] == "--workspace-probe") {
    var config = CodexJson.Read(args[1]); var saved = CodexJson.Read(args[2]);
    foreach (var row in saved["accounts"]!.AsObject()) {
        var original = row.Value!["auth"]!.AsObject();
        var probe = await CodexWorkspaceUsage.Read(config.S("executable"), Path.GetDirectoryName(args[1])!, original, default);
        var limits = probe.Usage["rateLimits"]!;
        Console.WriteLine($"Verified {limits.S("planType")}: 5h used {limits["primary"].S("usedPercent")}%, week used {limits["secondary"].S("usedPercent")}%; token unchanged {original.ToJsonString() == probe.Auth.ToJsonString()}");
    }
    return;
}
if (args.Length == 3 && args[0] == "--workspace-rpc-probe") {
    var config=CodexJson.Read(args[1]);var saved=CodexJson.Read(args[2]);
    foreach(var row in saved["accounts"]!.AsObject()) {
        var auth=row.Value!["auth"]!.AsObject();var identity=CodexAccountStore.Identity(auth);
        var probe=Path.Combine(Path.GetDirectoryName(args[1])!,"usage-probes",Guid.NewGuid().ToString("N"));
        try {
            CodexJson.Atomic(Path.Combine(probe,"auth.json"),auth);
            await using var client=new CodexRpc(config.S("executable"),probe,new Dictionary<string,string>(),_=>Task.CompletedTask,"cli_auth_credentials_store=\"file\"","forced_chatgpt_workspace_id="+JsonValue.Create(identity.S("workspace_id"))!.ToJsonString());
            using var limit=new CancellationTokenSource(TimeSpan.FromSeconds(15));await client.Initialize(limit.Token);
            var read=await client.Call("account/read",new JsonObject{["refreshToken"]=false},limit.Token);
            Console.WriteLine($"Native login {identity.S("plan_type")}: account present {read["account"]!=null}; routed workspace matches {read["workspaceRouting"].S("chatgptAccountId")==identity.S("workspace_id")}; credentials unchanged {CodexJson.Read(Path.Combine(probe,"auth.json")).ToJsonString()==auth.ToJsonString()}");
            if(read["account"]==null) throw new Exception("Native login missing");
        } finally { if(Directory.Exists(probe))Directory.Delete(probe,true); }
    }
    return;
}

if (args.Length == 3 && args[0] == "--probe") {
    var watch = System.Diagnostics.Stopwatch.StartNew();
    var result = CodexRolloutSnapshot.Read(args[1], args[2]); var first = watch.Elapsed.TotalMilliseconds;
    watch.Restart(); for (var i = 0; i < 100; i++) CodexRolloutSnapshot.Read(args[1], args[2]);
    Console.WriteLine($"Rollout read: {new FileInfo(args[2]).Length / 1024 / 1024} MiB; initial {first:F1} ms; cached average {watch.Elapsed.TotalMilliseconds / 100:F3} ms; context available {result["context"]?["available"]}");
    return;
}
if (args.Length == 3 && args[0] == "--message-times") {
    var source=CodexRolloutMessageTimes.Read(args[1],args[2]);long position=0;var found=0;var missing=0;
    var rows=JsonNode.Parse(Console.In.ReadToEnd())["data"].AsArray();
    foreach(var row in rows) {
        if(source.Resolve("",row["role"].ToString(),row["content"].ToString(),ref position,out var time)) {
            found++;
            if(found>rows.Count-20 || DateTimeOffset.FromUnixTimeMilliseconds(time)>DateTimeOffset.Parse("2026-10-06T01:00:00Z"))
                Console.WriteLine($"{row["role"]} {DateTimeOffset.FromUnixTimeMilliseconds(time):HH:mm:ss} UTC (matched source)");
        } else missing++;
    }
    Console.WriteLine($"Source text/time matches {found}; other/rewritten messages {missing}");return;
}

var catalog = JsonNode.Parse("""{"data":[{"model":"m","supportedReasoningEfforts":[{"reasoningEffort":"high"},{"reasoningEffort":"low"}],"serviceTiers":[{"id":"priority"}],"defaultReasoningEffort":"low","defaultServiceTier":"priority"}]}""")!.AsObject();
var count = 0;
void Check(bool valid, string name) { if (!valid) throw new Exception(name); count++; }
var authTest = Path.Combine(Path.GetTempPath(), "chuckie-workspace-contract-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(authTest);
try {
    JsonObject Auth(string workspace, string plan, string version) {
        var claims = new JsonObject { ["email"]="test@example.invalid", ["sub"]="fixture", ["https://api.openai.com/auth"]=new JsonObject { ["chatgpt_account_id"]=workspace, ["chatgpt_plan_type"]=plan } };
        var encoded=Convert.ToBase64String(System.Text.Encoding.UTF8.GetBytes(claims.ToJsonString())).TrimEnd('=').Replace('+','-').Replace('/','_');
        var jwt="e30."+encoded+".signature";
        return new JsonObject { ["tokens"]=new JsonObject { ["id_token"]=jwt, ["access_token"]=jwt, ["refresh_token"]=version, ["account_id"]=workspace } };
    }
    var homeTest=Path.Combine(authTest,"home");var authPath=Path.Combine(homeTest,"auth.json");
    var store=new CodexAccountStore(homeTest,Path.Combine(authTest,"store"));
    var teamAuth=Auth("team-id","team","team-original");var personalAuth=Auth("personal-id","plus","personal-original");
    CodexJson.Atomic(authPath,teamAuth);
    var teamId=store.Capture(teamAuth);var personalId=store.Capture(personalAuth);
    Check(store.List().S("current")==teamId,"workspace current identity comes from disk");
    Check(store.WorkspaceAuth(personalId).ToJsonString()==personalAuth.ToJsonString(),"personal usage never reuses current team token");
    var nextTeam=Auth("team-id","team","team-rotated");CodexJson.Atomic(authPath,nextTeam);store.List();
    var saved=CodexJson.Read(Path.Combine(authTest,"store","accounts.json"));
    Check(saved["accounts"]![teamId]!["auth"]!.ToJsonString()==nextTeam.ToJsonString(),"external token rotation recovered before later switch");
    store.ValidateWorkspaceSwitch(personalId);store.Use(personalId);
    Check(CodexAccountStore.Identity(store.CurrentAuth()).S("workspace_id")=="personal-id","workspace switch restores target identity");
    Check(store.WorkspaceAuth(teamId).ToJsonString()==nextTeam.ToJsonString(),"switch preserved latest source token");
    var nextPersonal=Auth("personal-id","plus","personal-rotated");store.RecoverWorkspaceAuth(personalId,personalAuth,nextPersonal);
    Check(store.CurrentAuth().ToJsonString()==nextPersonal.ToJsonString(),"isolated refresh updates still matching live auth");
    var newer=Auth("personal-id","plus","newer-login");store.Capture(newer);
    store.RecoverWorkspaceAuth(personalId,personalAuth,Auth("personal-id","plus","stale-probe"));
    Check(CodexJson.Read(Path.Combine(authTest,"store","accounts.json"))["accounts"]![personalId]!["auth"]!.ToJsonString()==newer.ToJsonString(),"stale probe cannot overwrite newer captured login");
    var crossed=Auth("team-id","team","x");crossed["tokens"]!["access_token"]=personalAuth["tokens"]!["access_token"]!.DeepClone();
    try { CodexAccountStore.Identity(crossed);Check(false,"crossed credentials rejected"); } catch(CodexError) { Check(true,"crossed credentials rejected"); }
    var quota=CodexWorkspaceUsage.Normalize(JsonNode.Parse("""{"plan_type":"team","rate_limit":{"primary_window":{"used_percent":12.5,"limit_window_seconds":18000,"reset_at":100},"secondary_window":{"used_percent":54,"limit_window_seconds":604800,"reset_at":200}}}""")!.AsObject());
    Check(quota["rateLimits"]!["primary"]!.L("windowDurationMins")==300 && quota["rateLimits"]!["secondary"]!.L("windowDurationMins")==10080,"workspace quota time windows normalized");
    Check(quota["rateLimits"]!["primary"]!.S("usedPercent")=="12.5" && quota["rateLimits"]!["secondary"]!.L("resetsAt")==200,"workspace quota values retain precision and reset times");
    CodexWorkspaceUsage.Verify(new JsonObject { ["plan_type"]="business" },quota);Check(true,"business and team plan names normalized");
    try { CodexWorkspaceUsage.Verify(new JsonObject { ["plan_type"]="plus" },quota);Check(false,"wrong subscription quota rejected"); } catch(CodexError) { Check(true,"wrong subscription quota rejected"); }
} finally { Directory.Delete(authTest,true); }
var selection = CodexModelSettings.Validate(JsonNode.Parse("""{"model":"m","provider":"custom","reasoning_effort":"high","service_tier":"priority"}""")!.AsObject(), catalog);
var streamed = new CodexAssistantMessageStream();
var streamTurn = JsonNode.Parse("""{"status":"inProgress","items":[{"id":"first","type":"agentMessage","phase":"commentary","text":"先检查"}]}""")!.AsObject();
var changes = streamed.Update(streamTurn).ToArray();
Check(changes.Select(row => row["event"].ToString()).SequenceEqual(new[]{"message.started","message.delta"}) && changes.All(row => row["item_id"].ToString() == "first"), "desktop stream identifies its first assistant item");
streamTurn["items"]![0]!["text"] = "先检查配置";
changes = streamed.Update(streamTurn).ToArray();
Check(changes.Length == 1 && changes[0]["delta"].ToString() == "配置", "same native item grows without adding another bubble");
streamTurn["items"]!.AsArray().Add(new JsonObject { ["id"]="tool", ["type"]="commandExecution" });
streamTurn["items"]!.AsArray().Add(new JsonObject { ["id"]="final", ["type"]="agentMessage", ["phase"]="final_answer", ["text"]="已完成" });
changes = streamed.Update(streamTurn).ToArray();
Check(changes.Any(row => row["event"].ToString() == "message.completed" && row["item_id"].ToString() == "first") && changes.Any(row => row["item_id"].ToString() == "final" && row["event"].ToString() == "message.started"), "tool boundary completes commentary before separate final item");
streamTurn["items"]![2]!["text"] = "替换而非追加";
changes = streamed.Update(streamTurn).ToArray();
Check(changes.Any(row => row["event"].ToString() == "message.snapshot" && row["text"].ToString() == "替换而非追加"), "rewritten native message replaces its snapshot");
Check(streamed.Update(streamTurn).Count() == 0, "unchanged desktop snapshot emits no duplicate messages");
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
    var messagePath=Path.Combine(temp,"message-times.jsonl");
    string Timed(string time,string id,string role,string text)=>new JsonObject {
        ["type"]="response_item",["timestamp"]=time,["payload"]=new JsonObject {
            ["type"]="message",["id"]=id,["role"]=role,["content"]=new JsonArray(new JsonObject{["type"]=role=="user"?"input_text":"output_text",["text"]=text})
        }
    }.ToJsonString();
    File.WriteAllText(messagePath,Timed("2026-10-06T01:03:00Z","raw-user-a","user","继续")+"\n"+
        Timed("2026-10-06T01:04:00Z","assistant-a","assistant","先检查")+"\n"+
        Timed("2026-10-06T01:19:00Z","raw-user-b","user","继续")+"\n");
    var messageTimes=CodexRolloutMessageTimes.Read(temp,messagePath);long messagePosition=0;
    Check(messageTimes.Resolve("synthetic-user-a","user","继续",ref messagePosition,out var atA) && atA==DateTimeOffset.Parse("2026-10-06T01:03:00Z").ToUnixTimeMilliseconds(),"synthetic user ID matches its ordered source message");
    Check(messageTimes.Resolve("assistant-a","assistant","different snapshot text",ref messagePosition,out var atAssistant) && atAssistant>atA,"exact assistant item identity overrides text matching");
    Check(messageTimes.Resolve("synthetic-user-b","user","继续",ref messagePosition,out var atB) && atB==DateTimeOffset.Parse("2026-10-06T01:19:00Z").ToUnixTimeMilliseconds(),"same-turn interjection retains its own 09:19 time rather than 09:03 turn start");
    File.AppendAllText(messagePath,Timed("2026-10-06T01:20:00Z","later","assistant","新消息"));
    messageTimes=CodexRolloutMessageTimes.Read(temp,messagePath);messagePosition=0;
    Check(!messageTimes.Resolve("later","assistant","新消息",ref messagePosition,out _),"partial rollout line waits for completion");
    File.AppendAllText(messagePath,"\n"+Timed("2026-10-06T01:30:00Z","assistant-a","assistant","重放")+"\n");
    messageTimes=CodexRolloutMessageTimes.Read(temp,messagePath);messagePosition=0;
    Check(messageTimes.Resolve("later","assistant","新消息",ref messagePosition,out var atLater) && atLater>atB,"incremental completion adds the new message");
    Check(messageTimes.Resolve("assistant-a","assistant","重放",ref messagePosition,out var original) && original==atAssistant,"replayed item keeps its original creation time");
    File.WriteAllText(messagePath,Timed("2026-10-06T01:31:00Z","replacement","user","新文件")+"\n");
    messageTimes=CodexRolloutMessageTimes.Read(temp,messagePath);messagePosition=0;
    Check(!messageTimes.Resolve("assistant-a","assistant","先检查",ref messagePosition,out _),"replaced rollout clears old timestamp cache");
    messagePosition=0;
    Check(!CodexRolloutMessageTimes.Read(temp,Path.Combine(Path.GetTempPath(),"outside.jsonl")).Resolve("replacement","user","新文件",ref messagePosition,out _),"timestamp reader respects home boundary");
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
    File.AppendAllText(activityPath, Record("event_msg", new() { ["type"] = "item_started", ["turn_id"] = "turn-2", ["started_at_ms"] = stamp+20000, ["item"] = new JsonObject { ["type"] = "ContextCompaction" } }, stamp+20000));
    snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["activity"]["kind"].ToString() == "compact" && snapshot["activity"]["phase_started_at"].GetValue<long>() == stamp+20000, "compaction has separate phase clock");
    File.AppendAllText(activityPath, Record("compacted", new() { ["compaction_response_id"] = "compact-id" }, stamp+21000));
    snapshot = CodexRolloutSnapshot.Read(temp, activityPath);
    Check(snapshot["activity"]["compacted_at"].GetValue<long>() == stamp+21000 && snapshot["activity"]["compaction_id"].ToString() == "compact-id", "native compaction completion can be displayed without sending a message");
    Check(snapshot["activity"]["kind"].ToString() == "task", "automatic compaction completion restores task phase");
    var desktop = JsonNode.Parse("""{"turns":[{"turnId":"old","turnStartedAtMs":1000,"status":"completed","items":[]},{"turnId":"compact","turnStartedAtMs":2000,"status":"inProgress","items":[{"type":"contextCompaction","completed":false}]}]}""")!.AsObject();
    var live = CodexDesktopActivity.Project(desktop);
    Check(live["running"].GetValue<bool>() && live["kind"].ToString() == "compact" && live["phase_started_at"].GetValue<long>() == 2000, "desktop manual compaction uses its own start time");
    desktop["turns"]![1]!["items"]!.AsArray().Insert(0, new JsonObject { ["type"] = "commandExecution" });
    live = CodexDesktopActivity.Project(desktop);
    Check(live["kind"].ToString() == "compact" && live["phase_started_at"] == null, "automatic compaction does not inherit old task timer when item start is unavailable");
    desktop["turns"]![1]!["status"] = "completed"; desktop["turns"]![1]!["items"]![1]!["completed"] = true;
    Check(!CodexDesktopActivity.Project(desktop)["running"].GetValue<bool>(), "completed desktop turn is idle despite a retained writer");
    Check(!CodexDesktopActivity.Project(new())["available"].GetValue<bool>(), "incomplete desktop snapshot cannot prove idle");
} finally { Directory.Delete(temp, true); }
Console.WriteLine($"Agent contract checks: {count} passed");
var input = "{\"type\":\"text\",\"text\":\"" + new string('汉', 2048) + "\"}";
using var ws = new FragmentSocket(System.Text.Encoding.UTF8.GetBytes(input));
Check(await ChuckieHelper.WebApi.Services.RemoteControl.WebSocketTextReader.Read(ws, new byte[4096], default) == input, "fragmented Chinese remote text");
using var oversized = new FragmentSocket(new byte[17000]);
Check(await ChuckieHelper.WebApi.Services.RemoteControl.WebSocketTextReader.Read(oversized, new byte[4096], default) == null && oversized.ClosedAs == System.Net.WebSockets.WebSocketCloseStatus.MessageTooBig, "oversized message bounded");
Console.WriteLine($"Including remote WebSocket checks: {count} passed");
