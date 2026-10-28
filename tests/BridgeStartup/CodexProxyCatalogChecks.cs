using System.Reflection;
using System.Text.Json;
using System.Text.Json.Nodes;

internal static class CodexProxyCatalogChecks
{
    // Isolated CLI home and an unreachable endpoint: validation must not require /models or inference.
    public static async Task Run(Assembly assembly, string executable) {
        var root = Path.Combine(Path.GetTempPath(), "codex-proxy-catalog-" + Guid.NewGuid().ToString("N"));
        var home = Path.Combine(root, "home"); Directory.CreateDirectory(home);
        var catalogPath = Path.Combine(root, "catalog.json");
        var builder = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexRuntimeCatalog", true)!.GetMethod("Build")!;
        JsonObject catalog = new();
        foreach (var model in new[] { "gpt-6-sol", "gpt-6-astra" }) {
            var metadata = JsonNode.Parse("""{"context_window":475000,"defaultReasoningEffort":"medium","supportedReasoningEfforts":[{"reasoningEffort":"medium"},{"reasoningEffort":"high"}]}""")!.AsObject();
            catalog = (JsonObject)builder.Invoke(null, [catalog, model, metadata])!;
        }
        File.WriteAllText(catalogPath, catalog.ToJsonString());
        File.WriteAllText(Path.Combine(home, "config.toml"), $"""
            model_provider = "custom"
            model = "gpt-6-astra"
            model_catalog_json = {JsonSerializer.Serialize(catalogPath)}
            [model_providers.custom]
            name = "custom"
            base_url = "http://127.0.0.1:1/v1"
            requires_openai_auth = true
            wire_api = "responses"
            """);
        var settings = Path.Combine(root, "connection.json");
        File.WriteAllText(settings, new JsonObject { ["home"] = home, ["state_folder"] = root,
            ["account_store"] = Path.Combine(root, "accounts"), ["executable"] = executable }.ToJsonString());
        var type = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexAgent", true)!;
        var agent = (IAsyncDisposable)type.GetConstructor(BindingFlags.NonPublic | BindingFlags.Instance, null, [typeof(string)], null)!.Invoke([settings]);
        async Task<JsonObject> Invoke(string method, params object[] args) =>
            await (Task<JsonObject>)type.GetMethod(method, BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(agent, args)!;
        try {
            await (Task)type.GetMethod("Launch", BindingFlags.NonPublic | BindingFlags.Instance)!.Invoke(agent, null)!;
            var discovered = await Invoke("ProviderCatalog", "custom", CancellationToken.None);
            if (!discovered["data"]!.AsArray().Any(row => row!["model"]!.ToString() == "gpt-6-sol"))
                throw new Exception("Old session model was lost from the active proxy's configured native catalog");
            var shown = await Invoke("Models");
            var custom = shown["providers"]!.AsArray().Single(row => row!["slug"]!.ToString() == "custom")!;
            if (!custom["models"]!.AsArray().Any(row => row!["model"]!.ToString() == "gpt-6-sol"))
                throw new Exception("UI catalog and send validation must agree on the old session model");
            var validate = assembly.GetType("RemoteTool.WebApi.Services.Codex.CodexModelSettings", true)!.GetMethod("Validate")!;
            var arguments = new List<object> { new JsonObject { ["provider"] = "custom", ["model"] = "gpt-6-sol", ["reasoning_effort"] = "medium" }, discovered };
            if (validate.GetParameters().Length == 3) arguments.Add(null);
            var selected = (JsonObject)validate.Invoke(null, arguments.ToArray())!;
            if (selected["modelProvider"]!.ToString() != "custom" || selected["model"]!.ToString() != "gpt-6-sol")
                throw new Exception("Validation must preserve the requested provider and model");
            var runs = (JsonObject)type.GetField("runs", BindingFlags.NonPublic | BindingFlags.Instance)!.GetValue(agent)!;
            if (runs.Count != 0) throw new Exception("Catalog verification must not submit a message or start inference");
            Console.WriteLine("PASS: real CLI custom catalog and UI/send validation preserve an old non-default model without inference");
        } finally { await agent.DisposeAsync(); Directory.Delete(root, true); }
    }
}
