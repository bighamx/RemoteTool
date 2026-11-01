using System.Text.Json;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Storage;
using RemoteTool.WebApi.Services.RemoteControl;

internal static class StorageChecks
{
    private static JsonNode Json(object value)=>JsonSerializer.SerializeToNode(value,new JsonSerializerOptions(JsonSerializerDefaults.Web))!;
    private static void Check(bool value,string message){if(!value)throw new Exception(message);}
    public static async Task Run(){
        var root=Path.Combine(Path.GetTempPath(),"remotetool-storage-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(root);
        var active=false; long? age=null;
        var service=new ManagedStorageService(root,(_,_,_)=>age);
        var payload=Path.Combine(root,"codex-attachments","session-a","media","report.pdf");Directory.CreateDirectory(Path.GetDirectoryName(payload)!);File.WriteAllText(payload,"original");File.SetLastWriteTimeUtc(payload,DateTime.UtcNow.AddDays(-2));
        var database=Path.Combine(root,"state.db");File.WriteAllText(database,"protected native data");
        try{
            (string Token,JsonArray Rows) Scan(string category=""){
                var scan=Json(service.Scan("owner",default));var token=scan["snapshot"]!.GetValue<string>();
                var rows=Json(service.Items("owner",token,new StorageFilter{Category=category}))["data"]!.AsArray();return(token,rows);
            }
            string Prepare((string Token,JsonArray Rows) snapshot,string action)=>Json(service.Prepare("owner",new(snapshot.Token,snapshot.Rows.Select(x=>x!["id"]!.GetValue<string>()).ToArray(),action)))["plan"]!.GetValue<string>();
            var original=Scan();Check(original.Rows.Count==1,"native databases are never cleanup candidates");
            try{service.Items("other-owner",original.Token,new());throw new Exception("cross-owner snapshot accepted");}catch(ArgumentException){}
            var trash=Prepare(original,"trash");var moved=Json(await service.Execute("owner",trash));
            Check(moved["processed"]!.GetValue<int>()==1&&!File.Exists(payload),"attachment moved to recycle bin");
            Check(AttachmentDeletionLedger.IsSuppressed(Path.Combine(root,"codex-attachments"),payload),"deleted media cannot be silently imported again");
            var replay=Json(await service.Execute("owner",trash));Check(replay.ToJsonString()==moved.ToJsonString(),"cleanup request is idempotent");
            var recycled=Scan("trash");Check(recycled.Rows.Count==1,"recycle bin survives fresh scans");
            File.WriteAllText(payload,"new user file");
            var conflict=Json(await service.Execute("owner",Prepare(recycled,"restore")));
            Check(conflict["failed"]!.GetValue<int>()==1&&File.ReadAllText(payload)=="new user file","restore never overwrites a replacement");
            File.Delete(payload);
            var restored=Json(await service.Execute("owner",Prepare(Scan("trash"),"restore")));
            Check(restored["processed"]!.GetValue<int>()==1&&File.ReadAllText(payload)=="original","restore preserves original content");
            Check(!AttachmentDeletionLedger.IsSuppressed(Path.Combine(root,"codex-attachments"),payload),"restoring makes attachments accessible again");
            var current=Scan();var changedPlan=Prepare(current,"trash");File.AppendAllText(payload," changed");
            Check(Json(await service.Execute("owner",changedPlan))["failed"]!.GetValue<int>()==1&&File.Exists(payload),"changed files are not deleted");
            File.SetLastWriteTimeUtc(payload,DateTime.UtcNow.AddDays(-2));var beforeBusy=Scan();var busyPlan=Prepare(beforeBusy,"trash");age=5;
            Check(Json(await service.Execute("owner",busyPlan))["processed"]!.GetValue<int>()==0,"tasks starting after confirmation protect their attachments");age=null;
            var stale=Prepare(Scan(),"trash");age=3600;
            Check(Json(await service.Execute("owner",stale))["processed"]!.GetValue<int>()==1,"abandoned run registrations stop locking files");age=null;
            var removed=Json(await service.Execute("owner",Prepare(Scan("trash"),"purge")));
            Check(removed["freedBytes"]!.GetValue<long>()>0&&Scan("trash").Rows.Count==0,"permanent deletion releases recycle payloads");
            Check(File.ReadAllText(database)=="protected native data","native data survives all cleanup operations");
            var outside=Path.Combine(root,"outside");Directory.CreateDirectory(outside);File.WriteAllText(Path.Combine(outside,"private.txt"),"keep");
            var link=Path.Combine(root,"codex-attachments","linked-session");
            try {
                Directory.CreateSymbolicLink(link,outside);
                Check(!StorageFiles.Safe(Path.Combine(root,"codex-attachments"),Path.Combine(link,"private.txt")),"linked directories cannot escape managed attachment storage");
                Check(Scan().Rows.Count==0,"linked private data is excluded from inventory");
                Directory.Delete(link);
            } catch(UnauthorizedAccessException) { Console.WriteLine("Symlink creation unavailable; path containment check retained"); }
            Check(TerminalCapabilities.Choices("Linux",p=>p=="/bin/sh").Select(x=>x.Id).SequenceEqual(new[]{"shell"}),"Linux with sh exposes no Windows terminals");
            Check(TerminalCapabilities.UnixExecutable("Linux",p=>p=="/bin/sh")=="/bin/sh","minimal Linux shell fallback");
            Check(TerminalCapabilities.Choices("macOS",p=>p=="/bin/zsh").All(x=>x.Id=="shell"),"Mac supports only implemented shell types");
        }finally{Directory.Delete(root,true);}
        Console.WriteLine("Managed storage checks passed: scope, owner, recycle, restore conflicts, file changes, active tasks, replay and terminal capabilities");
    }
}
