using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexModelSettings
{
    public static JsonObject Validate(JsonObject body, JsonObject catalog, JsonArray runtimeModels = null) {
        var model = body.S("model").Trim(); var provider = body.S("provider").Trim();
        if (model.Length is 0 or > 256 || provider.Length is 0 or > 80) throw new CodexError("请选择 Provider 和模型");
        var detail = catalog.A("data").FirstOrDefault(row => row.S("model", row.S("id")) == model);
        // 聚合模式（cc-switch stack）下模型 slug 由代理编码，provider 的发现结果里没有它；
        // 只要当前运行时目录里有这个模型就接受。
        var aggregateModel = false;
        if (detail == null && runtimeModels != null) {
            detail = runtimeModels.FirstOrDefault(row => row.S("model", row.S("id")) == model);
            aggregateModel = detail != null;
        }
        if (detail == null) throw new CodexError("该 Provider 未提供所选模型，请刷新模型列表");
        var result = Obj(("model", model), ("modelProvider", provider));
        var official = provider == "openai" || catalog.B("official_provider");
        if (official) result["officialProvider"] = true;
        if (aggregateModel) result["aggregateModel"] = true;
        if (!official && CodexRuntimeCatalog.Window(detail) > 0) result["modelMetadata"] = detail.DeepClone();
        if (provider != "openai" && catalog.S("base_url").Length > 0) result["providerBaseUrl"] = catalog.S("base_url");
        if (body.ContainsKey("reasoning_effort")) {
            var effort = body.S("reasoning_effort");
            if (effort.Length > 0 && !detail.A("supportedReasoningEfforts").Any(option => option.S("reasoningEffort") == effort))
                throw new CodexError("该模型未提供所选思考程度，请刷新模型列表");
            // Config overrides are TOML values, not nullable turn fields. A null override
            // becomes an empty effort and Codex rejects the entire configuration.
            var defaultEffort = detail.S("defaultReasoningEffort").Trim();
            result["reasoningEffort"] = effort.Length == 0 ? defaultEffort.Length > 0 ? defaultEffort : "medium" : effort;
        }
        if (!official) result["serviceTier"] = "default";
        else if (body.ContainsKey("service_tier")) {
            var tier = body.S("service_tier");
            if (tier == "standard") tier = "default";
            if (tier.Length > 0 && tier != "default" && !detail.A("serviceTiers").Any(option => option.S("id") == tier))
                throw new CodexError("该模型未提供所选速度档位，请刷新模型列表");
            result["serviceTier"] = tier.Length == 0 ? JsonValue.Create(detail.S("defaultServiceTier", "default")) : JsonValue.Create(tier);
        }
        return result;
    }
    public static void ApplyResume(JsonObject request, JsonObject selection) {
        request["model"] = selection["model"]?.DeepClone();
        request["modelProvider"] = selection["modelProvider"]?.DeepClone();
        if (selection.ContainsKey("serviceTier")) request["serviceTier"] = selection["serviceTier"]?.DeepClone();
        if (selection.S("reasoningEffort").Length > 0) {
            request["config"] ??= new JsonObject();
            request["config"]!["model_reasoning_effort"] = selection["reasoningEffort"]!.DeepClone();
        }
        if (selection.S("providerBaseUrl").Length > 0) {
            request["config"] ??= new JsonObject();
            request["config"]!["model_providers." + selection.S("modelProvider") + ".base_url"] = selection.S("providerBaseUrl");
        }
        if (selection["modelMetadata"] is JsonObject metadata && CodexRuntimeCatalog.Window(metadata) > 0) {
            request["config"] ??= new JsonObject();
            CodexRuntimeCatalog.ApplyContext(request["config"]!.AsObject(), metadata);
        }
    }
    public static void ApplyTurn(JsonObject request, JsonObject selection, JsonObject defaults, JsonObject collaborationMode = null) {
        request["model"] = selection["model"]?.DeepClone();
        // An explicit null resets to the model default rather than retaining the previous turn's effort.
        if (selection.ContainsKey("reasoningEffort")) request["effort"] = selection["reasoningEffort"]?.DeepClone() ?? defaults["defaultReasoningEffort"]?.DeepClone();
        if (selection.ContainsKey("serviceTier")) request["serviceTier"] = selection["serviceTier"]?.DeepClone() ?? defaults["defaultServiceTier"]?.DeepClone() ?? JsonValue.Create("default");
        // Desktop inherits collaboration settings; their embedded model/effort take precedence
        // over top-level overrides. Preserve the mode while updating its matching settings.
        if (collaborationMode != null) {
            var mode = collaborationMode.DeepClone().AsObject();
            mode["settings"] ??= new JsonObject();
            mode["settings"]!["model"] = request["model"]?.DeepClone();
            if (request.ContainsKey("effort")) mode["settings"]!["reasoning_effort"] = request["effort"]?.DeepClone();
            request["collaborationMode"] = mode;
        }
    }
}
