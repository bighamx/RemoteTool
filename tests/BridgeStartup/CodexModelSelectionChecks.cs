using System.Reflection;
using System.Text.Json.Nodes;

internal static class CodexModelSelectionChecks
{
    // Only config/model reads and an empty local thread. Never starts inference.
    public static async Task Run(Assembly assembly, string executable) {
        var root = Path.Combine(Path.GetTempPath(), "codex-selection-" + Guid.NewGuid().ToString("N"));
        var home = Path.Combine(root,"home"); Directory.CreateDirectory(home);
        var configPath = Path.Combine(home,"config.toml");
        File.WriteAllText(configPath,"""
            model = "gpt-6-sol"
            [model_providers.custom]
            name = "CC Switch"
            base_url = "http://127.0.0.1:15721/v1"
            wire_api = "responses"
            experimental_bearer_token = "PROXY_MANAGED"
            """);
        var settings = Path.Combine(root,"connection.json");
        File.WriteAllText(settings,new JsonObject{["home"]=home,["state_folder"]=root,["executable"]=executable,["token"]="fixture-only"}.ToJsonString());
        var type = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAgent",true)!;
        var agent = (IAsyncDisposable)type.GetConstructor(BindingFlags.NonPublic|BindingFlags.Instance,null,[typeof(string)],null)!.Invoke([settings]);
        object Field(string name) => type.GetField(name,BindingFlags.NonPublic|BindingFlags.Instance)!.GetValue(agent)!;
        async Task<T> Invoke<T>(string name,params object[] args) => await (Task<T>)type.GetMethod(name,BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(agent,args)!;
        async Task RpcVoid(string method) => await (Task)type.GetMethod(method,BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(agent,null)!;
        void Check(bool ok,string label){if(!ok)throw new Exception(label);Console.WriteLine("PASS: "+label);}
        try {
            await RpcVoid("Launch");
            var rpc = Field("rpc");
            async Task<JsonObject> Call(string method,JsonObject args) => await (Task<JsonObject>)rpc.GetType().GetMethod("Call")!.Invoke(rpc,[method,args,CancellationToken.None])!;
            var catalog = await Call("model/list",new());
            var model = catalog["data"]!.AsArray()[0]!["model"]!.ToString();
            var thread = await Call("thread/start",new JsonObject{["cwd"]=root});
            var id = thread["thread"]!["id"]!.ToString();
            var overrides = (Dictionary<string,JsonObject>)Field("sessionOverrides");
            JsonObject Old() => new(){["modelProvider"]="custom",["model"]="gpt-6-sol",["providerBaseUrl"]="http://127.0.0.1:15721/v1",["reasoningEffort"]="high"};
            overrides[id] = Old();
            var shown = await Invoke<JsonObject>("SessionSelection",id,false);
            Check(shown["modelProvider"]!.ToString()=="openai" && !shown.ContainsKey("providerBaseUrl"),"real bridge repairs stale managed proxy before displaying settings");
            var persisted = JsonNode.Parse(File.ReadAllText(Path.Combine(root,"model-selections.json")))![id]!;
            Check(persisted["modelProvider"]!.ToString()=="openai","repair persists to prevent recurrence after restart");
            var resume = await Invoke<JsonObject>("ResumeSessionRequest",id,true);
            Check(resume["modelProvider"]!.ToString()==shown["modelProvider"]!.ToString(),"displayed and actual resume provider agree");
            overrides[id] = Old();
            var requested = new JsonObject{["model_settings"]=new JsonObject{["provider"]="openai",["model"]=model}};
            var loaded = (Dictionary<string,JsonObject>)Field("loaded");
            loaded[id] = thread;
            await (Task)type.GetMethod("ApplyRequestedSelection",BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(agent,[id,requested,CancellationToken.None])!;
            Check(loaded.ContainsKey(id),"first-send settings preserve a new thread's only in-memory owner");
            resume = await Invoke<JsonObject>("ResumeSessionRequest",id,true);
            Check(resume["modelProvider"]!.ToString()=="openai" && resume["model"]!.ToString()==model,"submitted UI snapshot wins over a conflicting bridge cache");
            requested["model_settings"]!["provider"]="custom";
            try {
                await (Task)type.GetMethod("ApplyRequestedSelection",BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(agent,[id,requested,CancellationToken.None])!;
                throw new Exception("stale UI snapshot accepted");
            } catch(Exception error) when(error.GetType().Name=="CodexError") {
                Check(Equals(error.GetType().GetProperty("Code")!.GetValue(error),"model_settings_changed"),"stale explicit UI is rejected before writing a message");
            }
            Check(((JsonObject)Field("runs")).Count==0,"selection verification created no runs or inference requests");
        } finally {
            await agent.DisposeAsync();
            for(var attempt=0;;attempt++) {
                try {Directory.Delete(root,true);break;}
                catch(IOException) when(attempt<20){await Task.Delay(200);}
                catch(IOException){Console.WriteLine("Fixture cleanup deferred: "+root);break;}
            }
        }
    }
}
