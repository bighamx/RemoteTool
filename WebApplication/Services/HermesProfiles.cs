#nullable enable
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Filters;
using Microsoft.Extensions.Configuration;

namespace RemoteTool.WebApi.Services;

/// <summary>Immutable per-request identity, inherited by asynchronous work created by that request.</summary>
public static class HermesProfileScope
{
    private static readonly AsyncLocal<string?> current = new();
    public static string Name => current.Value ?? "default";
    public static string AgentKey => Name == "default" ? "hermes" : "hermes@" + Name;
    public static string Key(string value) => Name + ":" + value;
    public static string ApiRoot => Name == "default" ? "/api/hermes" : "/api/hermes/profiles/" + Name;
    public static IDisposable Enter(string name) { var previous = current.Value; current.Value = name; return new Restore(() => current.Value = previous); }
    private sealed class Restore(Action restore) : IDisposable { public void Dispose() => restore(); }
}

public sealed class HermesProfiles(IConfiguration configuration)
{
    public string Root => configuration["Hermes:HomeDirectory"] ?? RemoteToolPaths.HermesHome;
    public string Home => HomeFor(HermesProfileScope.Name);
    public string HomeFor(string name)
    {
        if (name == "default") return Root;
        if (!Regex.IsMatch(name, "^[a-z0-9][a-z0-9_-]{0,63}$")) throw new ArgumentException("无效的 Profile");
        var path = Path.Combine(Root, "profiles", name);
        if (!Directory.Exists(path) || (File.GetAttributes(path) & FileAttributes.ReparsePoint) != 0 ||
            !new[] { "config.yaml", ".env", "SOUL.md", "profile.yaml", "auth.json", "state.db" }.Any(file => File.Exists(Path.Combine(path, file))))
            throw new ArgumentException("Profile 不存在");
        return path;
    }
    public string? ApiKey(string name)
    {
        var path = name == "default"
            ? configuration["Hermes:KeyFile"] ?? Environment.GetEnvironmentVariable("HERMES_API_KEY_FILE") ?? RemoteToolPaths.HermesKeyFile
            : Path.Combine(HomeFor(name), ".env");
        if (!File.Exists(path)) return null;
        foreach (var line in File.ReadLines(path)) {
            var parts = line.Split('=', 2);
            if (parts.Length == 2 && parts[0].Trim() == "API_SERVER_KEY") return parts[1].Trim().Trim('"', '\'');
        }
        return null;
    }
    public JsonObject List()
    {
        var served = new HashSet<string>(StringComparer.Ordinal);
        try {
            var state = JsonNode.Parse(File.ReadAllText(Path.Combine(Root, "gateway_state.json")));
            if (state?["served_profiles"] is JsonArray names)
                foreach (var name in names) if (name?.ToString() is { } value) served.Add(value);
        } catch (Exception error) when (error is IOException or System.Text.Json.JsonException) { }
        // Older single-profile gateways do not publish served_profiles.
        if (served.Count == 0) served.Add("default");
        var rows = new JsonArray();
        var directory = Path.Combine(Root, "profiles");
        var namesToList = new[] { "default" }.Concat(Directory.Exists(directory)
            ? Directory.EnumerateDirectories(directory).Select(Path.GetFileName).OfType<string>().OrderBy(name => name)
            : Enumerable.Empty<string>());
        foreach (var name in namesToList) {
            try {
                HomeFor(name);
                var hasKey = !string.IsNullOrWhiteSpace(ApiKey(name));
                var isServed = served.Contains(name);
                rows.Add(new JsonObject { ["id"] = name, ["name"] = name, ["available"] = hasKey && isServed,
                    ["reason"] = !isServed ? "网关未服务此 Profile" : !hasKey ? "未配置 API 密钥" : "" });
            } catch (ArgumentException) { }
        }
        return new JsonObject { ["data"] = rows };
    }
}

public sealed class HermesProfileFilter(HermesProfiles profiles) : IAsyncActionFilter
{
    public async Task OnActionExecutionAsync(ActionExecutingContext context, ActionExecutionDelegate next)
    {
        var name = context.RouteData.Values["profile"]?.ToString() ?? "default";
        try { profiles.HomeFor(name); }
        catch (ArgumentException error) { context.Result = new NotFoundObjectResult(new { message = error.Message }); return; }
        using (HermesProfileScope.Enter(name)) await next();
    }
}
