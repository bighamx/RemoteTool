using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Abstractions;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.Configuration;
using RemoteTool.WebApi.Services;

internal static class HermesSetupChecks
{
    public static async Task Run() {
        var home = Path.Combine(Path.GetTempPath(), "rc-hermes-setup-" + Guid.NewGuid().ToString("N"));
        var prior = Environment.GetEnvironmentVariable("HERMES_API_KEY_FILE");
        Directory.CreateDirectory(home);
        try {
            Environment.SetEnvironmentVariable("HERMES_API_KEY_FILE", null);
            var envFile = Path.Combine(home, ".env");
            File.WriteAllText(envFile, "OTHER_KEY=not-the-api-key\nAPI_SERVER_KEY='fixture-only'\n");
            var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string> {
                ["Hermes:HomeDirectory"] = home
            }).Build();
            var profiles = new HermesProfiles(config);
            if (OperatingSystem.IsMacOS() && !File.Exists(RemoteToolPaths.HermesKeyFile) && profiles.ApiKey("default") != "fixture-only")
                throw new Exception("Mac must discover the installed Hermes .env by default");
            var explicitFile = Path.Combine(home, "api.env");
            File.WriteAllText(explicitFile, "API_SERVER_KEY=explicit-fixture\n");
            Environment.SetEnvironmentVariable("HERMES_API_KEY_FILE", explicitFile);
            if (profiles.ApiKey("default") != "explicit-fixture") throw new Exception("Explicit environment path must override Mac discovery");
            config["Hermes:KeyFile"] = Path.Combine(home, "missing.env");
            if (profiles.ApiKey("default") != null) throw new Exception("Explicit missing file must not borrow another API identity");
            HermesSetupRequired failure = null;
            try { await new HermesBridge(new NeverClient(), config).SendAsync(HttpMethod.Get, "api/sessions", null, null, CancellationToken.None); }
            catch (HermesSetupRequired error) { failure = error; }
            if (failure == null) throw new Exception("Missing credentials must be rejected before dispatch");
            var action = new ActionContext(new DefaultHttpContext(), new RouteData(), new ActionDescriptor());
            var filters = new List<IFilterMetadata>();
            var executing = new ActionExecutingContext(action, filters, new Dictionary<string, object>(), new object());
            var executed = new ActionExecutedContext(action, filters, new object()) { Exception = failure };
            await new HermesProfileFilter(profiles).OnActionExecutionAsync(executing, () => Task.FromResult(executed));
            var result = executed.Result as ObjectResult;
            var payload = System.Text.Json.JsonSerializer.SerializeToElement(result?.Value);
            if (!executed.ExceptionHandled || result?.StatusCode != 409 ||
                payload.GetProperty("code").GetString() != "hermes_setup_required" || payload.GetProperty("delivery").GetString() != "rejected")
                throw new Exception("Setup failure must produce actionable JSON for every Hermes endpoint");
            Console.WriteLine("Hermes setup: Mac discovery, explicit identity and rejected JSON error checks passed");
        } finally {
            Environment.SetEnvironmentVariable("HERMES_API_KEY_FILE", prior);
            Directory.Delete(home, true);
        }
    }
    private sealed class NeverClient : IHttpClientFactory
    {
        public HttpClient CreateClient(string name) => throw new Exception("Unconfigured requests must not leave the service");
    }
}
