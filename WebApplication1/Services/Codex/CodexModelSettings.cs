using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

internal static class CodexModelSettings
{
    public static JsonObject Validate(JsonObject body, JsonObject catalog) {
        var model = body.S("model").Trim(); var provider = body.S("provider").Trim();
        if (model.Length is 0 or > 256 || provider.Length is 0 or > 80) throw new CodexError("请选择 Provider 和模型");
        var detail = catalog.A("data").FirstOrDefault(row => row.S("model", row.S("id")) == model);
        var result = Obj(("model", model), ("modelProvider", provider));
        if (body.ContainsKey("reasoning_effort")) {
            var effort = body.S("reasoning_effort");
            if (effort.Length > 0 && !detail.A("supportedReasoningEfforts").Any(option => option.S("reasoningEffort") == effort))
                throw new CodexError("该模型未提供所选思考程度，请刷新模型列表");
            result["reasoningEffort"] = effort.Length == 0 ? null : JsonValue.Create(effort);
        }
        if (body.ContainsKey("service_tier")) {
            var tier = body.S("service_tier");
            if (tier == "standard") tier = "default";
            if (tier.Length > 0 && tier != "default" && !detail.A("serviceTiers").Any(option => option.S("id") == tier))
                throw new CodexError("该模型未提供所选速度档位，请刷新模型列表");
            result["serviceTier"] = tier.Length == 0 ? null : JsonValue.Create(tier);
        }
        return result;
    }
    public static void ApplyResume(JsonObject request, JsonObject selection) {
        request["model"] = selection["model"]?.DeepClone();
        request["modelProvider"] = selection["modelProvider"]?.DeepClone();
        if (selection.ContainsKey("serviceTier")) request["serviceTier"] = selection["serviceTier"]?.DeepClone();
        if (selection.ContainsKey("reasoningEffort")) request["config"] = Obj(("model_reasoning_effort", selection["reasoningEffort"]));
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
