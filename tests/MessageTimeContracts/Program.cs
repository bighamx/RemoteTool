using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

var lost=new JsonObject{["session_id"]="session",["owner"]="desktop",["turn_id"]="turn",
    ["status"]="acceptance_unknown",["error_code"]="run_tracking_lost"};
var observation=new JsonObject{["session_id"]="session",["available"]=true,["running"]=true,["activity_id"]="turn"};
if(!CodexDesktopRunResume.CanResume(lost,"session",observation))throw new Exception("Matching live mobile submission must reconnect");
foreach(var field in new[]{"session_id","activity_id","available","running"}) {
    var other=observation.DeepClone().AsObject();
    other[field]=field is "available" or "running"?JsonValue.Create(false):JsonValue.Create("other");
    if(CodexDesktopRunResume.CanResume(lost,"session",other))throw new Exception("Unrelated/completed desktop turn must never be adopted");
}
CodexDesktopRunResume.Restore(lost);
if(lost["status"]!.ToString()!="started" || lost.ContainsKey("error_code"))throw new Exception("Restore must reset tracking state");
Console.WriteLine("6 run ownership recovery contracts passed");

if(args.Length==4 && args[0]=="--verify-session") {
    var snapshot=CodexRolloutMessageTimes.ReadSession(args[1],args[2],"");long position=0;var count=0;
    foreach(var row in JsonNode.Parse(File.ReadAllText(args[3]))!.AsArray()) {
        if(!snapshot.Resolve(row!["id"]!.ToString(),row["role"]!.ToString(),row["text"]!.ToString(),ref position,out var time) ||
            time!=row["expected"]!.GetValue<long>())throw new Exception("Actual session ID timestamp mismatch");
        count++;
    }
    Console.WriteLine($"{count} actual historical message timestamps verified");return;
}

if(args.Length==3) {
    var snapshot=CodexRolloutMessageTimes.Read(args[0],args[1]); long position=0;
    foreach(var row in JsonNode.Parse(File.ReadAllText(args[2]))!.AsArray()) {
        if(!snapshot.Resolve("synthetic", "user", row!["text"]!.ToString(), ref position,out var time) ||
            time!=DateTimeOffset.Parse(row["timestamp"]!.ToString()).ToUnixTimeMilliseconds())
            throw new Exception("Actual uploaded image message timestamp mismatch");
    }
    Console.WriteLine("Actual uploaded-image message times verified"); return;
}
var temp=Path.Combine(Path.GetTempPath(),"chuckie-message-times-"+Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(temp);
try {
    JsonObject Text(string value)=>new(){["type"]="input_text",["text"]=value};
    var body="上传图片\n附件文件：\n\"C:\\test\\image.jpg\"";
    var parts=new JsonArray(Text(body), Text("<image name=[Image #1] path=\"C:\\test\\image.jpg\">"),
        new JsonObject{["type"]="input_image",["image_url"]="data:image/png;base64,AA=="},Text("</image>"));
    JsonObject Record(string id,JsonArray content)=>new(){["type"]="response_item",["timestamp"]="2026-10-07T08:04:29.262Z",
        ["payload"]=new JsonObject{["type"]="message",["id"]=id,["role"]="user",["content"]=content}};
    var literal="<image name=[Image #1] path=\"literal.jpg\">";
    var path=Path.Combine(temp,"messages.jsonl");
    File.WriteAllText(path,Record("image-message",parts).ToJsonString()+"\n"+Record("literal-message",new JsonArray(Text(literal))).ToJsonString()+"\n");
    var source=CodexRolloutMessageTimes.Read(temp,path);long position=0;
    if(!source.Resolve("synthetic-image","user",body,ref position,out var time) ||
        time!=DateTimeOffset.Parse("2026-10-07T08:04:29.262Z").ToUnixTimeMilliseconds())throw new Exception("Framing must not erase image message time");
    if(!source.Resolve("synthetic-literal","user",literal,ref position,out _))throw new Exception("Literal user image tags must remain searchable");
    position=0;
    if(source.Resolve("missing","user","上传图片",ref position,out _))throw new Exception("Partial content must not infer timestamps");
    Console.WriteLine("3 attachment timestamp contracts passed");
    var sessions=Path.Combine(temp,"sessions","2026","10","07");Directory.CreateDirectory(sessions);
    var first=Path.Combine(sessions,"rollout-2026-10-07T08-00-00-session.jsonl");
    var second=Path.Combine(sessions,"rollout-2026-10-07T08-01-00-session_native.jsonl");
    var older=Record("old",new JsonArray(Text("continue")));older["timestamp"]="2026-10-07T08:00:00Z";
    var newer=Record("new",new JsonArray(Text("continue")));newer["timestamp"]="2026-10-07T08:01:00Z";
    File.WriteAllText(first,older.ToJsonString()+"\n");
    File.WriteAllText(second,newer.ToJsonString()+"\n");
    var unrelated=Record("foreign",new JsonArray(Text("unrelated")));
    File.WriteAllText(Path.Combine(sessions,"rollout-2026-other.jsonl"),unrelated.ToJsonString()+"\n");
    var historical=CodexRolloutMessageTimes.ReadSession(temp,"session",second);position=0;
    if(!historical.Resolve("old","user","continue",ref position,out var oldTime) ||
        !historical.Resolve("new","user","continue",ref position,out var newTime) || oldTime>=newTime)
        throw new Exception("Exact IDs must retain distinct timestamps across continuation files");
    position=0;
    if(historical.Resolve("synthetic-old","user","continue",ref position,out _))
        throw new Exception("Repeated text without an ID must not guess an earlier message timestamp");
    if(!historical.Resolve("old","user","continue",ref position,out _) ||
        !historical.Resolve("synthetic-new","user","continue",ref position,out var matchedNew) || matchedNew!=newTime)
        throw new Exception("An exact preceding ID must disambiguate repeated text");
    position=0;if(historical.Resolve("foreign","user","unrelated",ref position,out _))throw new Exception("Other sessions must not supply times");
    var duplicate=older.DeepClone();duplicate["timestamp"]="2026-10-07T08:02:00Z";
    File.AppendAllText(second,duplicate.ToJsonString()+"\n");
    var reloaded=CodexRolloutMessageTimes.ReadSession(temp,"session",second);position=0;
    if(!reloaded.Resolve("old","user","continue",ref position,out var stable) || stable!=oldTime)
        throw new Exception("Copied history must not replace original ID timestamp");
    var appended=Record("appended",new JsonArray(Text("appended")));appended["timestamp"]="2026-10-07T08:03:00Z";
    File.AppendAllText(first,appended.ToJsonString()+"\n");
    position=0;if(!CodexRolloutMessageTimes.ReadSession(temp,"session",second).Resolve("appended","user","appended",ref position,out _))
        throw new Exception("Cached historical files must observe appended records");
    Console.WriteLine("5 continuation timestamp contracts passed");
} finally { Directory.Delete(temp,true); }
