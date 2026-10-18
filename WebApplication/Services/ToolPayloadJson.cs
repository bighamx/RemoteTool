using System.Text.Json;
using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

/// <summary>Model-produced tool JSON can contain repeated properties. Match the
/// upstream JSON decoder's last-value semantics before JsonNode's lazy lookup.</summary>
public static class ToolPayloadJson
{
    public static JsonNode Parse(string json)
    {
        using var document = JsonDocument.Parse(json);
        return Copy(document.RootElement);
    }

    private static JsonNode Copy(JsonElement value)
    {
        if (value.ValueKind == JsonValueKind.Object) {
            var result = new JsonObject();
            foreach (var property in value.EnumerateObject()) result[property.Name] = Copy(property.Value);
            return result;
        }
        if (value.ValueKind == JsonValueKind.Array)
            return new JsonArray(value.EnumerateArray().Select(Copy).ToArray());
        return JsonNode.Parse(value.GetRawText());
    }
}
