using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>Recover a named custom provider from the session's own endpoint, never global defaults.</summary>
public static class HermesProviderIdentity
{
    public static string Resolve(string provider, string baseUrl, JsonObject catalog)
    {
        if (!string.IsNullOrWhiteSpace(provider) && provider != "custom") return provider;
        if (!Uri.TryCreate(baseUrl, UriKind.Absolute, out var endpoint) ||
            endpoint.Scheme is not ("http" or "https") || endpoint.UserInfo.Length > 0 ||
            endpoint.Query.Length > 0 || endpoint.Fragment.Length > 0) return provider;
        var matches = (catalog["providers"] as JsonArray ?? new JsonArray()).OfType<JsonObject>()
            .Where(entry => entry["slug"]?.ToString().StartsWith("custom:", StringComparison.Ordinal) == true &&
                Uri.TryCreate(entry["api_url"]?.ToString(), UriKind.Absolute, out var candidate) &&
                candidate.Scheme == endpoint.Scheme && candidate.Host == endpoint.Host && candidate.Port == endpoint.Port &&
                candidate.UserInfo.Length == 0 && candidate.Query.Length == 0 && candidate.Fragment.Length == 0 &&
                candidate.AbsolutePath.TrimEnd('/') == endpoint.AbsolutePath.TrimEnd('/'))
            .Select(entry => entry["slug"]!.ToString()).Distinct(StringComparer.Ordinal).ToArray();
        // Several credentials/profiles may share a URL. Do not guess among them.
        return matches.Length == 1 ? matches[0] : provider;
    }
}
