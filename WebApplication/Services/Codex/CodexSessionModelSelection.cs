using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexSessionModelSelection
{
    public static bool ManagedProxy(JsonNode definition) => definition.S("experimental_bearer_token") == "PROXY_MANAGED";
    // CC Switch leaves its managed local endpoint on disk when the user switches
    // back to official routing. A dormant table is not a session routing choice.
    public static bool DormantManagedProxy(string provider, JsonObject config) {
        var definition = config["model_providers"]?[provider];
        return provider != config.S("model_provider", "openai") &&
            ManagedProxy(definition) &&
            Uri.TryCreate(definition.S("base_url"), UriKind.Absolute, out var address) && address.IsLoopback;
    }

    public static JsonObject Reconcile(JsonObject saved, JsonObject config, JsonObject nativeCatalog = null) {
        var selection = saved.DeepClone().AsObject();
        var provider = selection.S("modelProvider");
        var current = config.S("model_provider", "openai");
        var definition = config["model_providers"]?[provider];
        var official = CodexProviderModels.IsOfficial(provider, definition);
        var reroute = provider.Length == 0 || provider != "openai" && definition == null || DormantManagedProxy(provider, config);
        if (reroute) {
            provider = current;
            definition = config["model_providers"]?[provider];
            official = CodexProviderModels.IsOfficial(provider, definition);
            selection["modelProvider"] = provider;
            selection.Remove("providerBaseUrl");
            selection.Remove("modelMetadata");
            if (!official || nativeCatalog != null && !nativeCatalog.A("data").Any(row => row.S("model", row.S("id")) == selection.S("model")))
                selection["model"] = config.S("model");
        }
        if (official) {
            selection["officialProvider"] = true;
            selection.Remove("providerBaseUrl");
            selection.Remove("modelMetadata");
        } else {
            selection.Remove("officialProvider");
            selection["serviceTier"] = "default";
            // A cached discovery /v1 suffix is useful, but an old host or other
            // endpoint must never override a provider edited by the user.
            var endpoint = selection.S("providerBaseUrl").TrimEnd('/');
            var configured = definition.S("base_url").TrimEnd('/');
            if (endpoint.Length > 0 && endpoint != configured && endpoint != configured + "/v1") {
                selection.Remove("providerBaseUrl");
                selection.Remove("modelMetadata");
            }
        }
        return selection;
    }

    public static bool RoutingChanged(JsonObject before, JsonObject after) =>
        before.S("modelProvider") != after.S("modelProvider") || before.S("providerBaseUrl") != after.S("providerBaseUrl") ||
        before["modelMetadata"]?.ToJsonString() != after["modelMetadata"]?.ToJsonString();

    public static void RequireCurrentRequest(JsonObject requested, JsonObject config) {
        if (DormantManagedProxy(requested.S("provider"), config))
            throw new CodexError("所选代理配置已停用，请刷新模型配置后重新发送；本次消息未写入。", 409, "model_settings_changed", "rejected");
    }
}
