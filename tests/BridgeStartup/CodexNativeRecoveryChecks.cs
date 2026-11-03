using System.Reflection;
using System.Text.Json.Nodes;
using Microsoft.AspNetCore.Http;

internal static class CodexNativeRecoveryChecks
{
    // The production RPC transport talks to this isolated stdio fixture. No real account or inference.
    public static async Task FakeCli() {
        var home=Environment.GetEnvironmentVariable("CODEX_HOME")!;
        var fixture=JsonNode.Parse(File.ReadAllText(Path.Combine(home,"native-fixture.json")))!;
        while(await Console.In.ReadLineAsync() is {} line) {
            var request=JsonNode.Parse(line)!;var method=request["method"]?.ToString();
            if(request["id"]==null)continue;
            await File.AppendAllTextAsync(Path.Combine(home,"calls.log"),method+"\n");
            var response=new JsonObject{["id"]=request["id"]!.DeepClone()};
            if(method=="thread/read")response["result"]=new JsonObject{["thread"]=new JsonObject{["id"]="session"}};
            else if(method=="thread/turns/list") {
                var cursor=request["params"]?["cursor"]?.ToString()??"";
                var mode=fixture["mode"]!.ToString();
                if(mode=="error")response["error"]=new JsonObject{["code"]=-32000,["message"]="fixture history unavailable"};
                else if(cursor.Length==0)response["result"]=new JsonObject{
                    ["data"]=new JsonArray(new JsonObject{["id"]="new-turn",["status"]="inProgress"}),["nextCursor"]="older"};
                else response["result"]=new JsonObject{
                    ["data"]=mode=="missing"?new JsonArray():new JsonArray(new JsonObject{["id"]="old-turn",["status"]=mode}),
                    ["nextCursor"]=mode=="missing"?null:"must-not-read-more"};
            } else response["error"]=new JsonObject{["code"]=-32601,["message"]="Unexpected mutation: "+method};
            await Console.Out.WriteLineAsync(response.ToJsonString());await Console.Out.FlushAsync();
        }
    }

    public static async Task Run(Assembly assembly) {
        var root=Path.Combine(Path.GetTempPath(),"codex-native-recovery-"+Guid.NewGuid().ToString("N"));
        var cli=Path.Combine(root,"cli");Directory.CreateDirectory(cli);
        var host=Path.Combine(AppContext.BaseDirectory,OperatingSystem.IsWindows()?"BridgeStartup.exe":"BridgeStartup");
        foreach(var file in Directory.EnumerateFiles(AppContext.BaseDirectory,"BridgeStartup*")) {
            if(File.Exists(file))File.Copy(file,Path.Combine(cli,Path.GetFileName(file)));
        }
        var executable=Path.Combine(cli,Path.GetFileName(host));
        if(!OperatingSystem.IsWindows())File.SetUnixFileMode(executable,UnixFileMode.UserRead|UnixFileMode.UserWrite|UnixFileMode.UserExecute);
        foreach(var name in new[]{"codex-code-mode-host.exe","codex-command-runner.exe"})File.WriteAllText(Path.Combine(cli,name),"fixture");
        var flags=BindingFlags.NonPublic|BindingFlags.Instance;
        var type=assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAgent",true)!;
        var rpcType=assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexRpc",true)!;
        var activity=assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexDesktopActivity",true)!;
        var cache=(System.Collections.IDictionary)activity.GetField("cache",BindingFlags.NonPublic|BindingFlags.Static)!.GetValue(null)!;
        var entryType=activity.GetNestedType("Entry",BindingFlags.NonPublic)!;
        var checks=0;
        try {
            foreach(var mode in new[]{"completed","failed","interrupted","missing","error","inProgress"})
            foreach(var endpoint in new[]{"status","lookup","events"}) {
                var home=Path.Combine(root,mode+"-"+endpoint);Directory.CreateDirectory(home);
                File.WriteAllText(Path.Combine(home,"native-fixture.json"),new JsonObject{["mode"]=mode}.ToJsonString());
                var config=Path.Combine(home,"connection.json");
                File.WriteAllText(config,new JsonObject{["home"]=home,["state_folder"]=home,["account_store"]=Path.Combine(home,"accounts")}.ToJsonString());
                var agent=(IAsyncDisposable)type.GetConstructor(flags,null,[typeof(string)],null)!.Invoke([config]);
                var rpc=(IAsyncDisposable)rpcType.GetConstructors()[0].Invoke([executable,home,
                    new Dictionary<string,string>{{"REMOTETOOL_NATIVE_RECOVERY_FIXTURE","1"}},
                    (Func<JsonObject,Task>)(_=>Task.CompletedTask),Array.Empty<string>()]);
                type.GetField("rpc",flags)!.SetValue(agent,rpc);
                var runs=(JsonObject)type.GetField("runs",flags)!.GetValue(agent)!;
                var active=(Dictionary<string,string>)type.GetField("active",flags)!.GetValue(agent)!;
                var loaded=(Dictionary<string,JsonObject>)type.GetField("loaded",flags)!.GetValue(agent)!;
                runs["old-run"]=new JsonObject{["run_id"]="old-run",["session_id"]="session",["turn_id"]="old-turn",
                    ["status"]="acceptance_unknown",["error"]="lost",["key"]="fixture_request_key"};
                // Both managed runs and legacy desktop receipts must reach the native history fallback.
                if(endpoint=="lookup")runs["old-run"]!["owner"]="desktop";
                runs["new-run"]=new JsonObject{["run_id"]="new-run",["status"]="started"};active["session"]="new-run";loaded["session"]=new JsonObject();
                var cacheKey=Path.GetFullPath(home)+"\nsession";
                var observation=new JsonObject{["available"]=true,["running"]=true,["activity_id"]="new-turn",
                    ["turn_statuses"]=new JsonObject{["new-turn"]="inProgress"}};
                cache[cacheKey]=Activator.CreateInstance(entryType,Environment.TickCount64,Task.FromResult(observation))!;
                try {
                    var context=new DefaultHttpContext();context.Request.Method="GET";context.Response.Body=new MemoryStream();
                    if(endpoint=="events")await (Task)type.GetMethod("Stream",flags)!.Invoke(agent,[context,"old-run"])!;
                    else {
                        context.Request.Path=endpoint=="status"?"/runs/old-run":"/runs/lookup";
                        if(endpoint=="lookup")context.Request.QueryString=new QueryString("?key=fixture_request_key");
                        await (Task<JsonObject>)type.GetMethod("Route",flags)!.Invoke(agent,[context,new JsonObject()])!;
                    }
                    var expected=mode is "completed" or "failed" or "interrupted"?mode:"acceptance_unknown";
                    if(runs["old-run"]!["status"]!.ToString()!=expected)throw new Exception("Native history reconciliation failed: "+mode+" "+endpoint);
                    if(active["session"]!="new-run" || !loaded.ContainsKey("session"))throw new Exception("Old receipt displaced the newer live owner");
                    var calls=File.ReadAllLines(Path.Combine(home,"calls.log"));
                    if(calls.Any(call=>call is not ("thread/read" or "thread/turns/list")))throw new Exception("Recovery dispatched a mutation");
                    if(mode is "completed" or "failed" or "interrupted" && calls.Count(call=>call=="thread/turns/list")!=2)throw new Exception("History must stop at the exact target page");
                    checks++;
                } finally {cache.Remove(cacheKey);await agent.DisposeAsync();}
            }
            Console.WriteLine($"PASS: {checks} real-RPC status/lookup/SSE reconnect cases, exact pagination and newer-task preservation");
        } finally {
            if(!Path.GetFullPath(root).StartsWith(Path.GetFullPath(Path.GetTempPath()),StringComparison.OrdinalIgnoreCase) || !Path.GetFileName(root).StartsWith("codex-native-recovery-",StringComparison.Ordinal))throw new Exception("Unexpected fixture cleanup path");
            for(var attempt=0;;attempt++) {
                try {Directory.Delete(root,true);break;}
                catch(IOException) when(attempt<5){await Task.Delay(100);}
                catch(UnauthorizedAccessException) when(attempt<5){await Task.Delay(100);}
            }
        }
    }
}
