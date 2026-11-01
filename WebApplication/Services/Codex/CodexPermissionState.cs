using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexPermissionState
{
    public static JsonObject Describe(JsonNode value,string source,string description) {
        var sandbox=value?["sandbox"] ?? value?["sandbox_policy"];
        var approval=value?["approvalPolicy"] ?? value?["approval_policy"];
        var type=sandbox is JsonObject?sandbox.S("type"):sandbox?.ToString()??"";
        if(type.Length==0 || approval==null)return null;
        var scope=type switch {
            "dangerFullAccess" or "danger-full-access"=>"完全文件访问",
            "workspaceWrite" or "workspace-write"=>"工作区访问",
            "readOnly" or "read-only"=>"只读访问",
            "externalSandbox" or "external-sandbox"=>"外部沙箱",
            _=>"自定义沙箱（"+type+"）"
        };
        var policy=approval is JsonValue?approval.ToString():"granular";
        var approvalLabel=policy switch {"never"=>"不再审批","on-request"=>"按需审批","untrusted"=>"不可信命令需审批","on-failure"=>"失败后审批",_=>"自定义审批"};
        var mode=type is "dangerFullAccess" or "danger-full-access" && policy=="never"?"full":
            type is "workspaceWrite" or "workspace-write" && policy=="on-request"?"workspace":"custom";
        var label=scope+" · "+approvalLabel;
        if(sandbox is JsonObject s && (s["networkAccess"] ?? s["network_access"]) is JsonValue network && network.TryGetValue<bool>(out var enabled))
            label+=" · "+(enabled?"允许联网":"网络受限");
        return Obj(("mode",mode),("label",label),("source",source),("description",description),
            ("sandbox",sandbox),("approvalPolicy",approval));
    }
    public static JsonObject Configuration(JsonNode config) => Describe(Obj(("sandbox",config?["sandbox_mode"]),
        ("approvalPolicy",config?["approval_policy"])),"configuration","服务端配置默认值；不是会话实际权限记录");
}
