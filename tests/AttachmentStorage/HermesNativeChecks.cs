using Microsoft.Extensions.Configuration;
using Microsoft.AspNetCore.Http;
using RemoteTool.WebApi.Services;
using System.Text.Json;
using System.Text.Json.Nodes;
using SQLite;

internal static class HermesNativeChecks {
    public static async Task Run() {
        var fixture=Path.Combine(Path.GetTempPath(),"hermes-native-attachments-"+Guid.NewGuid().ToString("N"));
        var home=Path.Combine(fixture,"hermes"); Directory.CreateDirectory(home);
        var root=Path.Combine(fixture,"uploads");
        var config=new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string,string>{{"Hermes:HomeDirectory",home}}).Build();
        var profiles=new HermesProfiles(config);
        var store=new HermesAttachments("hermes",root);
        var native=new HermesNativeAttachments(profiles,store);
        var bytes=Convert.FromBase64String("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");
        var data="data:image/png;base64,"+Convert.ToBase64String(bytes);
        void Check(bool ok,string name) {if(!ok)throw new Exception(name);}
        JsonNode View(long id,string text="caption")=>native.Present("s",new JsonObject { ["data"]=new JsonArray(new JsonObject { ["id"]=id,["role"]="user",["content"]=text }) }.ToJsonString())["data"][0];
        try {
            SQLitePCL.Batteries_V2.Init();
            using(var db=new SQLiteConnection(Path.Combine(home,"state.db"))) {
                db.Execute("CREATE TABLE messages(id INTEGER PRIMARY KEY,session_id TEXT,role TEXT,content TEXT)");
                var parts=new JsonArray(new JsonObject{{"type","text"},{"text","caption"}},new JsonObject{{"type","image_url"},{"image_url",new JsonObject{{"url",data}}}});
                db.Execute("INSERT INTO messages VALUES(1,'s','user',char(0)||'json:'||?)",parts.ToJsonString());
            }
            var inline=View(1);
            Check(inline["attachments"].AsArray().Count==1,"inline base64 image must be restored from Hermes database alone");
            var inlineId=inline["attachments"][0]["id"].ToString();
            Check(native.Resolve("s",inlineId).Bytes.SequenceEqual(bytes),"inline picture must remain downloadable without an original file");
            Check(!Directory.Exists(root),"reading Hermes history must not create RemoteTool attachment state");
            try {native.Resolve("other",inlineId);throw new Exception("cross session access accepted");} catch(FileNotFoundException){}
            Check(new HermesNativeAttachments(profiles,new HermesAttachments("hermes",root)).Resolve("s",inlineId).Bytes.SequenceEqual(bytes),"new service instance needs no private binding/cache data");

            using var stream=new MemoryStream(bytes);
            var upload=JsonSerializer.SerializeToNode(await store.Upload("s",new FormFile(stream,0,bytes.Length,"file","space 图片.png"),default));
            var path=store.Resolve("s",upload["id"].ToString());
            var reference=HermesAttachments.NativeReference(path);
            using(var db=new SQLiteConnection(Path.Combine(home,"state.db"))) {
                db.Execute("INSERT INTO messages VALUES(2,'s','user',?)","steer\n\n"+reference);
                db.Execute("INSERT INTO messages VALUES(3,'s','user',?)","caption\n[Image attached at: "+path+"]");
                var secret=Path.Combine(fixture,"secret.png"); File.WriteAllBytes(secret,bytes);
                db.Execute("INSERT INTO messages VALUES(4,'s','user',?)","@image:"+JsonSerializer.Serialize(secret));
            }
            File.WriteAllText(Path.Combine(store.Folder("s"),".chuckie-message-attachments.json"),"corrupt unrelated map");
            var steered=View(2,"steer\n\n"+reference);
            var steerId=steered["attachments"][0]["id"].ToString();
            Check(steered["content"].ToString()=="steer" && native.Resolve("s",steerId).Path==path,"steer card and download derive only from Hermes user message");
            Check(View(3)["attachments"].AsArray().Count==1,"native Image attached at form must be understood");
            Check(View(4)["attachments"].AsArray().Count==0,"quoted arbitrary files must not become downloadable");
            using(var db=new SQLiteConnection(Path.Combine(home,"state.db"))) {
                var duplicate=new JsonArray(new JsonObject{{"type","text"},{"text","caption\n[Image attached at: "+path+"]"}},
                    new JsonObject{{"type","image_url"},{"image_url",new JsonObject{{"url",data}}}});
                db.Execute("INSERT INTO messages VALUES(5,'s','user',char(0)||'json:'||?)",duplicate.ToJsonString());
            }
            Check(View(5)["attachments"].AsArray().Count==1,"inline bytes and native path hints describe one picture, not duplicate cards");
            var blob=Path.Combine(home,"cache","documents","native-inputs",new string('a',64));
            Directory.CreateDirectory(Path.GetDirectoryName(blob)!); File.WriteAllBytes(blob,bytes);
            using(var db=new SQLiteConnection(Path.Combine(home,"state.db")))
                db.Execute("INSERT INTO messages VALUES(6,'s','user',?)","@image:"+JsonSerializer.Serialize(blob));
            Check(View(6)["attachments"][0]["mime"].ToString()=="image/png","Hermes content-addressed images need no filename extension");
            var child=Path.Combine(home,"profiles","feiyu"); Directory.CreateDirectory(child); File.WriteAllText(Path.Combine(child,"config.yaml"),"");
            using(HermesProfileScope.Enter("feiyu")) {
                try { native.Resolve("s",inlineId); throw new Exception("cross profile image accepted"); } catch(FileNotFoundException){}
            }
            var body=JsonSerializer.SerializeToElement(new {session_id="s",input="again",attachment_ids=new[]{inlineId}});
            var prepared=JsonNode.Parse(native.PrepareSteer(body,"s","native-steer-key-1").GetRawText());
            Check(prepared["input"].ToString().Contains("@image:"),"embedded native images can be attached to a new interjection without sidecars");
            try {native.PrepareSteer(body,"other","native-steer-key-1");throw new Exception("cross session steer accepted");}catch(ArgumentException){}
            File.Delete(path);
            Check(View(2)["attachments"].AsArray().Count==0,"a missing path must not generate an invalid attachment card");
            Check(native.Resolve("s",inlineId).Bytes.SequenceEqual(bytes),"database embedded images survive uploaded file deletion");
            Console.WriteLine("Hermes native attachment checks passed: inline bytes, file refs, interjection, reload, resend, isolation, missing files, and no binding dependency.");
        } finally {Directory.Delete(fixture,true);}
    }
}
