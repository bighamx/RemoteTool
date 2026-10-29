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
            File.WriteAllText(configPath,"model_provider = \"custom\"\nmodel = \"ccs-relay/not-an-official-model\"\nmodel_catalog_json = \"broken-custom-catalog.json\"\n[model_providers.custom]\nname = \"Relay\"\nbase_url = \"http://127.0.0.1:9/v1\"\nwire_api = \"responses\"\n");
            var official = await Invoke<JsonObject>("OfficialCatalog",CancellationToken.None);
            Check(official["data"]!.AsArray().Count>0&&!official["data"]!.AsArray().Any(row=>row!["model"]!.ToString().StartsWith("ccs-")),"official catalog uses an isolated home even with an invalid custom catalog configured");
            var authPath=Path.Combine(home,"auth.json");
            File.WriteAllText(authPath,"{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"fixture-key-1\"}");
            var authTime=File.GetLastWriteTimeUtc(authPath);
            await Invoke<JsonObject>("OfficialCatalog",CancellationToken.None);
            var firstStamp=(string)Field("officialCatalogAuthStamp");
            File.WriteAllText(authPath,"{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"fixture-key-2\"}");
            File.SetLastWriteTimeUtc(authPath,authTime);
            await Invoke<JsonObject>("OfficialCatalog",CancellationToken.None);
            Check(firstStamp!=(string)Field("officialCatalogAuthStamp"),"same-length credential replacement invalidates official cache even with unchanged timestamp");
            File.Delete(authPath);
            await Invoke<JsonObject>("OfficialCatalog",CancellationToken.None);
            Check(firstStamp!=(string)Field("officialCatalogAuthStamp"),"logout invalidates official catalog cache");
            using(var cancelled=new CancellationTokenSource()) {
                cancelled.Cancel();
                try {await Invoke<JsonObject>("OfficialCatalog",cancelled.Token);throw new Exception("cancelled catalog probe succeeded");}
                catch(OperationCanceledException){Console.WriteLine("PASS: cancelled official catalog read stops before probe");}
            }
            async Task Invalidate() => await (Task)type.GetMethod("InvalidateOfficialCatalog",BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(agent,[CancellationToken.None])!;
            var probeSettings=(JsonObject)Field("settings");
            probeSettings["executable"]=Path.Combine(root,"missing-codex.exe");
            try {
                await Invalidate();
                async Task<Exception> FailedProbe() {
                    try {await Invoke<JsonObject>("OfficialCatalog",CancellationToken.None);throw new Exception("invalid probe executable accepted");}
                    catch(Exception error) when(error.GetType().Name=="CodexError") {return error;}
                }
                var firstFailure=await FailedProbe();
                Check(ReferenceEquals(firstFailure,await FailedProbe()),"official probe failure is reused during the negative-cache interval");
                type.GetField("officialCatalogCacheAt",BindingFlags.NonPublic|BindingFlags.Instance)!.SetValue(agent,DateTimeOffset.UtcNow.ToUnixTimeSeconds()-46);
                Check(!ReferenceEquals(firstFailure,await FailedProbe()),"expired failure cache permits another probe");
                var cachedFailure=Field("officialCatalogFailure");
                await Invalidate();
                Check(!ReferenceEquals(cachedFailure,await FailedProbe()),"manual refresh bypasses cached official probe failure");
            } finally {probeSettings["executable"]=executable;await Invalidate();}
            var routing = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexOfficialRouting",true)!;
            await (Task)routing.GetMethod("PrepareWorkspace")!.Invoke(null,[home,CancellationToken.None])!;
            await (ValueTask)rpc.GetType().GetMethod("DisposeAsync")!.Invoke(rpc,null)!;
            await RpcVoid("Launch");rpc=Field("rpc");
            var directConfig=(await Call("config/read",new JsonObject{["includeLayers"]=false}))["config"]!;
            Check((directConfig["model_provider"]?.ToString()??"openai")=="openai"&&string.IsNullOrEmpty(directConfig["model_catalog_json"]?.ToString()),"restarted real runtime reads direct official routing without a custom catalog");
            var directModels=await Call("model/list",new());
            Check(directModels["data"]!.AsArray().Count>0&&!directModels["data"]!.AsArray().Any(row=>row!["model"]!.ToString().StartsWith("ccs-")),"official recovery exposes native model slugs instead of aggregate-only names");
            var catalogs = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexRuntimeCatalog",true)!;
            var providerPath = (string)catalogs.GetMethod("WriteProvider")!.Invoke(null, [root,"custom",
                new JsonArray(new JsonObject { ["model"]="deepseek-test", ["context_window"]=1000000 }),"deepseek-test"])!;
            await (ValueTask)rpc.GetType().GetMethod("DisposeAsync")!.Invoke(rpc,null)!;
            File.WriteAllText(configPath,"model_provider = \"custom\"\nmodel = \"deepseek-test\"\nmodel_catalog_json = " + JsonValue.Create(providerPath)!.ToJsonString() +
                "\n[model_providers.custom]\nname = \"Fixture\"\nbase_url = \"http://127.0.0.1:9/v1\"\nwire_api = \"responses\"\n");
            await RpcVoid("Launch");rpc=Field("rpc");
            var thirdPartyModels=(await Call("model/list",new()))["data"]!.AsArray();
            Check(thirdPartyModels.Count==1 && thirdPartyModels[0]!["model"]!.ToString()=="deepseek-test",
                "real Codex reads the provider-only catalog without GPT cache entries");
            var beforeSwitch=(await Call("config/read",new JsonObject{["includeLayers"]=false}))["config"]!.AsObject();
            type.GetMethod("RememberProviderModel",BindingFlags.NonPublic|BindingFlags.Instance)!.Invoke(agent,[beforeSwitch]);
            var savedProviders=JsonNode.Parse(File.ReadAllText(Path.Combine(root,"providers.json")))!;
            Check(savedProviders["custom"]!["model"]!.ToString()=="deepseek-test",
                "third-party model is saved while its runtime is still available");
            overrides[id]=new JsonObject { ["modelProvider"]="custom", ["model"]="deepseek-session" };
            var scopedOptions=await Invoke<JsonObject>("Models",CancellationToken.None,id);
            var scopedProvider=scopedOptions["providers"]!.AsArray().Single(row=>row!["slug"]!.ToString()=="custom")!;
            Check(scopedProvider["models"]!.AsArray().Any(row=>row!["model"]!.ToString()=="deepseek-session"),"model options supplement only the requested session's saved model");
            Check(scopedProvider["slug"]!.ToString()==scopedProvider["id"]!.ToString() && scopedProvider["is_current"]!.ToString()==scopedProvider["current"]!.ToString(),"both provider field contracts are consistent");
            var globalOptions=await Invoke<JsonObject>("Models",CancellationToken.None,null!);
            Check(!globalOptions["providers"]!.AsArray().Single(row=>row!["slug"]!.ToString()=="custom")!["models"]!.AsArray().Any(row=>row!["model"]!.ToString()=="deepseek-session"),"session model does not leak into global model options");
        } finally {
            await agent.DisposeAsync();
            for(var attempt=0;;attempt++) {
                try {
                    // Background Git initialization can finish after RPC disposal.
                    foreach(var file in Directory.EnumerateFiles(root,"*",SearchOption.AllDirectories))
                        File.SetAttributes(file,File.GetAttributes(file)&~FileAttributes.ReadOnly);
                    Directory.Delete(root,true);break;
                }
                catch(Exception error) when((error is IOException or UnauthorizedAccessException) && attempt<20){await Task.Delay(200);}
                catch(Exception error) when(error is IOException or UnauthorizedAccessException){Console.WriteLine("Fixture cleanup deferred: "+root);break;}
            }
        }
    }
}
