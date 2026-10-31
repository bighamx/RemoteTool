using System.Text.Json.Nodes;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexApproval
{
    public static JsonObject Describe(string key, JsonObject request) {
        var method = request.S("method"); var p = request["params"];
        var choices = new JsonArray();
        void Add(string id, string label, string detail = "") => choices.Add(Obj(("id", id), ("label", label), ("detail", detail)));
        if (!method.EndsWith("requestUserInput")) {
            Add("once", "仅允许本次");
            if (method is "item/commandExecution/requestApproval" or "item/fileChange/requestApproval" or "item/permissions/requestApproval")
                Add("session", "本会话允许", "在 Codex 当前会话内记住本次授权；其他操作仍可能需要确认。");
            if (method == "item/commandExecution/requestApproval") {
                var rule = p.A("proposedExecpolicyAmendment");
                if (rule.Count > 0) Add("always", "总是允许此类命令", string.Join(" ", rule.Select(v => v?.ToString())));
                var network = p.A("proposedNetworkPolicyAmendments");
                for (var i = 0; i < network.Count; i++)
                    if (network[i].S("action") == "allow" && network[i].S("host").Length > 0)
                        Add("network:" + i, "总是允许联网：" + network[i].S("host"), "保存 Codex 提议的主机联网规则。");
            }
            Add("deny", "拒绝");
        }
        return Obj(("request_id", key), ("kind", method.EndsWith("requestUserInput") ? "user_input" : "approval"),
            ("description", p.S("reason", p.S("command", "Codex 请求你的确认"))), ("command", p.S("command")),
            ("questions", p.A("questions")), ("choices", choices));
    }

    public static JsonObject Answer(JsonObject request, JsonObject body) {
        var method = request.S("method"); var p = request["params"]; var choice = body.S("choice");
        if (method.EndsWith("requestUserInput")) return Obj(("answers", body["answers"] ?? new JsonObject()));
        if (!Describe("", request).A("choices").Any(row => row.S("id") == choice))
            throw new CodexError("此审批不支持所选授权范围，请刷新后重试");
        if (method == "item/permissions/requestApproval")
            return Obj(("permissions", choice is "once" or "session" ? p?["permissions"] ?? new JsonObject() : new JsonObject()),
                ("scope", choice == "session" ? "session" : "turn"));
        if (choice == "always") return Obj(("decision", Obj(("acceptWithExecpolicyAmendment",
            Obj(("execpolicy_amendment", p.A("proposedExecpolicyAmendment")))))));
        if (choice.StartsWith("network:") && int.TryParse(choice[8..], out var index))
            return Obj(("decision", Obj(("applyNetworkPolicyAmendment",
                Obj(("network_policy_amendment", p.A("proposedNetworkPolicyAmendments")[index]))))));
        return Obj(("decision", choice == "once" ? "accept" : choice == "session" ? "acceptForSession" : "decline"));
    }
}
