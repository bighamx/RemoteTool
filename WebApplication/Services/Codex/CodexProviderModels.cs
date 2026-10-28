using System.Collections.Concurrent;
using System.Net.Http.Headers;
using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexProviderModels
{
    private static readonly ConcurrentDictionary<string, (DateTime Until, JsonObject Value)> cache = new();
    private static readonly HttpClient http = new(new HttpClientHandler { AllowAutoRedirect = false, UseProxy = false });
    private static readonly string[] efforts = ["none", "minimal", "low", "medium", "high", "xhigh"];

    internal static bool IsOfficial(string id, JsonNode provider) => id == "openai" ||
        provider.S("name") == "OpenAI" && provider.B("requires_openai_auth") &&
        provider.S("base_url").Length == 0 && provider.S("env_key").Length == 0 &&
        provider.S("experimental_bearer_token").Length == 0 && provider?["auth"] == null &&
        (provider?["http_headers"] as JsonObject)?.Count is not > 0 &&
        (provider?["env_http_headers"] as JsonObject)?.Count is not > 0;

    internal static bool UsesNativeCatalog(string provider, JsonObject config) =>
        IsOfficial(provider, config["model_providers"]?[provider]) ||
        provider == config.S("model_provider", "openai") && !string.IsNullOrWhiteSpace(config.S("model_catalog_json"));

    internal static JsonArray MergeOfficialAliases(JsonArray rows, string current) {
        var official = rows.Where(row => row.B("official_provider")).ToArray();
        if (official.Length == 0) return rows;
        var selected = official.FirstOrDefault(row => row.S("slug") == current) ?? official.FirstOrDefault(row => row.S("slug") == "openai") ?? official[0];
        var merged = selected.DeepClone().AsObject();
        merged["name"] = "OpenAI / ChatGPT";
        merged["aliases"] = new JsonArray(official.Select(row => (JsonNode)JsonValue.Create(row.S("slug"))).ToArray());
        return new JsonArray(rows.Where(row => !row.B("official_provider")).Select(row => row.DeepClone()).Append(merged).ToArray());
    }

    internal static Uri BaseUri(string address) {
        if (!Uri.TryCreate(address.TrimEnd('/'), UriKind.Absolute, out var uri) ||
            uri.Scheme is not ("http" or "https") || uri.UserInfo.Length > 0 || uri.Query.Length > 0 || uri.Fragment.Length > 0)
            throw new CodexError("Provider API 地址无效");
        var root = uri.AbsoluteUri.TrimEnd('/');
        if (root.EndsWith("/responses", StringComparison.OrdinalIgnoreCase)) root = root[..^10];
        if (root.EndsWith("/models", StringComparison.OrdinalIgnoreCase)) root = root[..^7];
        return new Uri(root);
    }
    internal static Uri Endpoint(string address) => new(BaseUri(address).AbsoluteUri.TrimEnd('/') + "/models");

    internal static JsonArray Normalize(JsonNode response) {
        var models = new JsonArray(); var seen = new HashSet<string>();
        foreach (var row in response.A("data").Concat(response.A("models"))) {
            var id = row is JsonValue ? row.ToString() : row.S("id", row.S("model", row.S("name")));
            if (string.IsNullOrWhiteSpace(id) || id.Length > 256 || !seen.Add(id)) continue;
            var model = Obj(("id", id), ("model", id), ("defaultReasoningEffort", "medium"),
                ("supportedReasoningEfforts", new JsonArray(efforts.Select(e => (JsonNode)Obj(("reasoningEffort", e))).ToArray())));
            if (row is JsonObject) {
                var window = row.L("context_length");
                if (window <= 0) window = row.L("context_window");
                if (window is > 0 and <= 1_000_000_000) model["context_window"] = window;
                var output = row.L("max_output_tokens");
                if (output is > 0 and <= 1_000_000_000) model["max_output_tokens"] = output;
                foreach (var field in new[] { "supports_images", "supports_tool_call", "supports_reasoning" })
                    if (row[field] is JsonValue value && value.TryGetValue<bool>(out var flag)) model[field] = flag;
            }
            models.Add(model);
            if (models.Count == 1000) break;
        }
        return models;
    }

    internal static JsonObject Catalog(string provider, JsonObject config, JsonNode saved, JsonObject native, JsonObject discovered) {
        // An explicit CLI catalog belongs to the active routing endpoint, including CC Switch.
        if (UsesNativeCatalog(provider, config)) return Obj(("official_provider", IsOfficial(provider, config["model_providers"]?[provider])),
            ("data", new JsonArray(native.A("data").Where(m => !m.B("hidden")).Select(m => m.DeepClone()).ToArray())));
        var models = discovered?.A("data").DeepClone().AsArray() ?? new JsonArray();
        // Only explicitly configured models supplement this endpoint's own catalog.
        foreach (var model in new[] { saved.S("model"), provider == config.S("model_provider") ? config.S("model") : "" })
            if (model.Length > 0 && !models.Any(m => m.S("model") == model))
                models.Add(Normalize(Obj(("data", new JsonArray(model))))[0]!.DeepClone());
        return Obj(("data", models), ("models_warning", discovered.S("models_warning")), ("base_url", discovered.S("base_url")));
    }

    internal static async Task<JsonObject> Discover(JsonNode provider, JsonNode saved, CancellationToken ct = default, HttpClient client = null) {
        var token = saved.S("api_key");
        if (token.Length == 0) token = provider.S("experimental_bearer_token");
        if (token.Length == 0 && provider.S("env_key").Length > 0) token = Environment.GetEnvironmentVariable(provider.S("env_key")) ?? "";
        var headers = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var pair in provider?["http_headers"] as JsonObject ?? new()) headers[pair.Key] = pair.Value?.ToString() ?? "";
        foreach (var pair in provider?["env_http_headers"] as JsonObject ?? new()) {
            var value = Environment.GetEnvironmentVariable(pair.Value?.ToString() ?? "");
            if (!string.IsNullOrEmpty(value)) headers[pair.Key] = value;
        }
        var key = Hash(provider?.ToJsonString() + token + string.Join(";", headers.Select(p => p.Key + "=" + p.Value)));
        if (client == null && cache.TryGetValue(key, out var hit) && hit.Until > DateTime.UtcNow) return hit.Value.DeepClone().AsObject();
        JsonObject result;
        try {
            using var timeout = CancellationTokenSource.CreateLinkedTokenSource(ct); timeout.CancelAfter(TimeSpan.FromSeconds(8));
            var root = BaseUri(provider.S("base_url"));
            for (var attempt = 0; ; attempt++) {
                using var request = new HttpRequestMessage(HttpMethod.Get, Endpoint(root.AbsoluteUri));
                if (token.Length > 0) request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
                foreach (var pair in headers) request.Headers.TryAddWithoutValidation(pair.Key, pair.Value);
                using var response = await (client ?? http).SendAsync(request, timeout.Token);
                if (response.StatusCode == System.Net.HttpStatusCode.NotFound && attempt == 0 && root.AbsolutePath == "/") {
                    root = new Uri(root, "v1"); continue;
                }
                if (!response.IsSuccessStatusCode) throw new CodexError($"获取供应商模型失败（HTTP {(int)response.StatusCode}），暂仅显示已配置模型");
                var data = Normalize(JsonNode.Parse(await response.Content.ReadAsStringAsync(timeout.Token)));
                if (data.Count == 0) throw new CodexError("供应商未返回模型列表，暂仅显示已配置模型");
                result = Obj(("data", data), ("base_url", root.AbsoluteUri.TrimEnd('/'))); break;
            }
        } catch (OperationCanceledException) when (!ct.IsCancellationRequested) {
            result = Obj(("models_warning", "获取供应商模型超时，暂仅显示已配置模型"));
        } catch (Exception error) when (error is HttpRequestException or System.Text.Json.JsonException or CodexError) {
            result = Obj(("models_warning", error is CodexError ? error.Message : "无法读取供应商模型列表，暂仅显示已配置模型"));
        }
        if (client == null) cache[key] = (DateTime.UtcNow.AddSeconds(result.A("data").Count > 0 ? 60 : 10), result.DeepClone().AsObject());
        return result;
    }
}
