using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services;

internal static class ProviderPolicyChecks {
    public static void Run(){
        var root=Path.Combine(Path.GetTempPath(),"provider-policy-"+Guid.NewGuid().ToString("N"));
        try{
            var policy=new AgentProviderPolicy(Path.Combine(root,"policy.json"),true);
            var options=JsonNode.Parse("""{"providers":[{"slug":"openai-api","name":"OpenAI","authenticated":true},{"slug":"unused","authenticated":false},{"slug":"custom:demo","name":"Demo","is_user_defined":true,"authenticated":false}]}""")!.AsObject();
            var endpoints=JsonNode.Parse("""{"endpoints":[{"id":"demo","name":"Demo","base_url":"http://localhost:9","model":"m"}]}""")!.AsObject();
            void Check(bool value,string message){if(!value)throw new Exception(message);}
            var visible=HermesProviderCatalog.Merge(options,endpoints,policy);
            Check(visible["providers"]!.AsArray().Count==2,"only configured native providers and explicit user additions are visible");
            Check(visible["providers"]![1]!["endpoint"]!["id"]!.ToString()=="demo","management matches the canonical native slug to its editable custom endpoint");
            policy.Set("custom:demo",false);
            Check(!new AgentProviderPolicy(Path.Combine(root,"policy.json"),true).Enabled("custom:demo"),"disabled canonical identities survive a new process");
            Check(policy.Enabled("demo"),"a custom provider must not disable a same-named built-in provider");
            Check(HermesProviderCatalog.Merge(options,endpoints,policy)["providers"]!.AsArray().Count==1,"disabled providers are excluded from model selection");
            var management=HermesProviderCatalog.Merge(options,endpoints,policy,true)["providers"]!.AsArray();
            Check(management.Count==2&&management[1]!["enabled"]!.GetValue<bool>()==false,"the explicit management toggle permits re-enabling disabled rows");
            policy.Set("custom:demo",true);Check(HermesProviderCatalog.Merge(options,endpoints,policy)["providers"]!.AsArray().Count==2,"enabling restores the same provider without copying credentials");
            var collision=JsonNode.Parse("""{"providers":[{"slug":"demo","name":"Demo","authenticated":true},{"slug":"custom:demo","name":"Demo","is_user_defined":true}]}""")!.AsObject();
            var matched=HermesProviderCatalog.Merge(collision,endpoints,policy)["providers"]!.AsArray();
            Check(matched[0]!["editable"]!.GetValue<bool>()==false&&matched[1]!["editable"]!.GetValue<bool>(),"same display names cannot make built-in credentials editable or bind to another endpoint");
            Check(!File.ReadAllText(Path.Combine(root,"policy.json")).Contains("base_url"),"availability state contains no credentials or native config copies");
        }finally{if(Directory.Exists(root))Directory.Delete(root,true);}
        Console.WriteLine("Provider policy checks passed: shared catalog, aliases, disabled selection, restoration and credential isolation");
    }
}
