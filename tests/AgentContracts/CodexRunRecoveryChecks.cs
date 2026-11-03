using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexRunRecoveryChecks {
    public static void Run() {
        var orphan=new JsonObject{["status"]="started",["approval"]=new JsonObject()};
        if(!CodexRunRecovery.Recover(orphan,false)||orphan.S("status")!="acceptance_unknown"||orphan.ContainsKey("approval"))throw new Exception("Orphan recovery must retain uncertainty and remove stale approval");
        var live=new JsonObject{["status"]="started"};
        if(CodexRunRecovery.Recover(live,true)||live.S("status")!="started")throw new Exception("Live ownership must remain active");
        var finished=new JsonObject{["status"]="started"};
        if(!CodexRunRecovery.Recover(finished,false,"interrupted")||finished.S("status")!="interrupted")throw new Exception("Actual history confirms terminal status");
        foreach(var status in new[]{"completed","failed","interrupted"}) {
            var unknown=new JsonObject{["status"]="acceptance_unknown",["error_code"]="run_tracking_lost"};
            if(!CodexRunRecovery.Recover(unknown,false,status)||unknown.S("status")!=status||unknown.ContainsKey("error_code"))throw new Exception("Exact native result must settle an unknown receipt");
        }
        if(new CodexError("Rejected",409).Delivery!="rejected" || new CodexError("No acknowledgement",504).Delivery!="unknown")throw new Exception("A timeout cannot prove a message was rejected");
        foreach(var status in new[]{"","inProgress","unknown"}) {
            var unknown=new JsonObject{["status"]="acceptance_unknown",["error_code"]="run_tracking_lost"};var before=unknown.ToJsonString();
            if(CodexRunRecovery.Recover(unknown,false,status)||unknown.ToJsonString()!=before)throw new Exception("Missing terminal evidence must retain uncertainty");
        }
        Console.WriteLine("PASS: native terminal recovery, live ownership and missing-evidence contracts");
    }
}
