using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

internal static class CodexApprovalChecks
{
    public static void Run() {
        void Check(bool ok, string name) { if (!ok) throw new Exception(name); }
        var command = Obj(("method", "item/commandExecution/requestApproval"), ("params", Obj(
            ("proposedExecpolicyAmendment", new JsonArray("dotnet", "build")),
            ("proposedNetworkPolicyAmendments", new JsonArray(Obj(("action", "deny"), ("host", "denied.test")),
                Obj(("action", "allow"), ("host", "api.nuget.org")))))));
        var before = command.ToJsonString();
        Check(CodexApproval.Answer(command, Obj(("choice", "once"))).S("decision") == "accept", "single approval");
        Check(CodexApproval.Answer(command, Obj(("choice", "session"))).S("decision") == "acceptForSession", "session-native approval");
        Check(CodexApproval.Answer(command, Obj(("choice", "deny"))).S("decision") == "decline", "denied approval");
        var always = CodexApproval.Answer(command, Obj(("choice", "always"), ("execpolicy_amendment", new JsonArray("evil"))));
        Check(always["decision"]?["acceptWithExecpolicyAmendment"]?["execpolicy_amendment"]?.ToJsonString() == "[\"dotnet\",\"build\"]", "rule comes from the native request, never client input");
        var network = CodexApproval.Answer(command, Obj(("choice", "network:1"), ("host", "evil.test")));
        Check(network["decision"]?["applyNetworkPolicyAmendment"]?["network_policy_amendment"].S("host") == "api.nuget.org", "host rule cannot be widened by client");
        foreach (var choice in new[] { "network:0", "network:99", "full", "unknown" }) {
            try { CodexApproval.Answer(command, Obj(("choice", choice))); throw new Exception("invalid approval accepted: " + choice); }
            catch (CodexError) { }
        }
        Check(command.ToJsonString() == before, "retries must preserve the original approval");
        var file = Obj(("method", "item/fileChange/requestApproval"), ("params", new JsonObject()));
        Check(CodexApproval.Answer(file, Obj(("choice", "session"))).S("decision") == "acceptForSession", "native file session grant");
        Check(!CodexApproval.Describe("id", file).A("choices").Any(x => x.S("id") == "always"), "no fabricated persistent rule");
        var permissionProfile = Obj(("network", Obj(("enabled", true))));
        var permission = Obj(("method", "item/permissions/requestApproval"), ("params", Obj(("permissions", permissionProfile))));
        var grant = CodexApproval.Answer(permission, Obj(("choice", "session"), ("permissions", Obj(("fileSystem", "evil")))));
        Check(grant.S("scope") == "session" && grant["permissions"]?["network"].B("enabled") == true && grant["permissions"]?["fileSystem"] == null, "session grants only requested permissions");
        Check(CodexApproval.Answer(permission, Obj(("choice", "deny")))["permissions"]!.AsObject().Count == 0, "decline grants no permissions");
        var folder = Path.Combine(Path.GetTempPath(), "codex-permissions-" + Guid.NewGuid().ToString("N"));
        try {
            var path = Path.Combine(folder, "permissions.json");
            var store = new CodexSessionPermissions(path);
            var original = Obj(("model", "test")); store.ApplyTurn("a", original);
            Check(!File.Exists(path) && original.Count == 1, "unspecified permissions do not mutate existing host policy");
            store.Set("a", "full");
            var request = Obj(("threadId", "a")); new CodexSessionPermissions(path).ApplyTurn("a", request);
            Check(request.S("approvalPolicy") == "never" && request["sandboxPolicy"].S("type") == "dangerFullAccess", "full access survives bridge restart");
            Check(store.Get("b") == "default", "grant cannot leak to another session");
            var snapshot = store.Get("a"); store.Set("a", "workspace");
            var accepted = new JsonObject(); CodexSessionPermissions.Apply(snapshot, accepted);
            Check(accepted.S("approvalPolicy") == "never", "settings saved during submission do not mutate an in-flight turn");
            var restored = new JsonObject(); store.ApplyTurn("a", restored);
            Check(restored.S("approvalPolicy") == "on-request" && restored["sandboxPolicy"].S("type") == "workspaceWrite", "workspace mode replaces previously granted full access");
            store.Remove("a"); Check(new CodexSessionPermissions(path).Get("a") == "default", "deleted sessions retain no permission override");
        } finally { if (Directory.Exists(folder)) Directory.Delete(folder, true); }
        Console.WriteLine("Codex approval and permission checks passed");
    }
}
