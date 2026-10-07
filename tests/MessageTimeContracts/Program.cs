using System.Text.Json.Nodes;
using ChuckieHelper.WebApi.Services.Codex;

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
} finally { Directory.Delete(temp,true); }
