using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

public static class HermesProviderCatalog
{
    private static bool Flag(JsonNode row,string key)=>row?[key] is JsonValue v&&v.TryGetValue<bool>(out var b)&&b;
    private static string Text(JsonNode row,string key)=>row?[key]?.ToString()??"";
    private static string Identity(string value){while(value.StartsWith("custom:",StringComparison.OrdinalIgnoreCase))value=value[7..];return value.Trim();}
    private static bool Matches(JsonObject native,JsonObject endpoint){
        var names=new[]{Text(native,"slug"),Text(native,"name")}.Concat((native["aliases"] as JsonArray??new()).Select(x=>x?.ToString()??""))
            .Select(Identity).ToHashSet(StringComparer.OrdinalIgnoreCase);
        return names.Contains(Identity(Text(endpoint,"id")))||names.Contains(Identity(Text(endpoint,"name")));
    }
    public static JsonObject Merge(JsonObject options,JsonObject endpoints,AgentProviderPolicy policy,bool includeDisabled=false){
        var result=options.DeepClone().AsObject();var rows=new JsonArray();
        var editable=(endpoints["endpoints"] as JsonArray??new()).OfType<JsonObject>().ToArray();
        foreach(var native in (options["providers"] as JsonArray??new()).OfType<JsonObject>()){
            if(!Flag(native,"authenticated")&&!Flag(native,"configured")&&!Flag(native,"enabled")&&!Flag(native,"is_user_defined")&&!Flag(native,"is_current"))continue;
            var row=native.DeepClone().AsObject();var id=Text(row,"slug");if(id.Length==0)continue;
            var custom=Flag(native,"is_user_defined")||id.StartsWith("custom:",StringComparison.Ordinal);
            var matches=custom?editable.Where(e=>Matches(native,e)).ToArray():Array.Empty<JsonObject>();
            row["editable"]=matches.Length==1;
            if(matches.Length==1)row["endpoint"]=matches[0].DeepClone();
            row["enabled"]=policy.Enabled(id);
            if(includeDisabled||Flag(row,"enabled"))rows.Add(row);
        }
        foreach(var endpoint in editable.Where(e=>!(options["providers"] as JsonArray??new()).OfType<JsonObject>().Any(n=>(Flag(n,"is_user_defined")||Text(n,"slug").StartsWith("custom:",StringComparison.Ordinal))&&Matches(n,e)))){
            var id=Text(endpoint,"provider_slug");
            if(id.Length==0)id=Text(endpoint,"source")=="custom_providers"?"custom:"+Text(endpoint,"name").ToLowerInvariant():Text(endpoint,"id");
            if(id.Length==0)continue;
            var row=new JsonObject{["slug"]=id,["name"]=Text(endpoint,"name"),["is_user_defined"]=true,["editable"]=true,
                ["endpoint"]=endpoint.DeepClone(),["enabled"]=policy.Enabled(id),["models"]=(endpoint["models"]??new JsonArray()).DeepClone(),["authenticated"]=Flag(endpoint,"has_api_key")};
            if(includeDisabled||Flag(row,"enabled"))rows.Add(row);
        }
        result["providers"]=rows;
        result["catalog_scope"]="enabled_or_added";
        return result;
    }
}
