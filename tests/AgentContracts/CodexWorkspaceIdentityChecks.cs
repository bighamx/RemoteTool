using System.Text;
using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexWorkspaceIdentityChecks
{
    private static string Token(string workspace, string plan, string subject = "same-user") {
        var claims = new JsonObject { ["chatgpt_account_id"] = workspace };
        if (plan != null) claims["chatgpt_plan_type"] = plan;
        var payload = new JsonObject { ["sub"] = subject, ["email"] = "fixture@example.invalid", ["https://api.openai.com/auth"] = claims };
        var encoded = Convert.ToBase64String(Encoding.UTF8.GetBytes(payload.ToJsonString())).TrimEnd('=').Replace('+', '-').Replace('/', '_');
        return "e30." + encoded + ".signature";
    }
    private static JsonObject Auth(string workspace, string plan) => new() {
        ["tokens"] = new JsonObject { ["account_id"] = workspace, ["id_token"] = Token(workspace, plan),
            ["access_token"] = Token(workspace, plan), ["refresh_token"] = "fixture-refresh" }
    };
    private static void Check(bool valid, string message) { if (!valid) throw new Exception(message); }
    private static void Rejected(JsonObject auth, string message) {
        try { CodexAccountStore.Identity(auth); }
        catch (CodexError) { return; }
        throw new Exception(message);
    }
    public static void Run() {
        var team = Auth("team-workspace", "team"); var personal = Auth("personal-workspace", "plus");
        var switched = team.DeepClone().AsObject();
        switched["tokens"]!["id_token"] = personal["tokens"]!["id_token"]!.DeepClone();
        var original = switched.ToJsonString();
        var identity = CodexAccountStore.Identity(switched);
        Check(identity.S("workspace_id") == "team-workspace" && identity.S("plan_type") == "team", "Selected workspace and plan must come from the current access token");
        Check(switched.ToJsonString() == original, "Identity parsing must not rewrite native credentials");
        var missingRoute = switched.DeepClone().AsObject(); missingRoute["tokens"]!["account_id"] = "";
        Check(CodexAccountStore.Identity(missingRoute).S("workspace_id") == "team-workspace", "Legacy empty routes must use the access token workspace");
        missingRoute["tokens"]!.AsObject().Remove("account_id");
        Check(CodexAccountStore.Identity(missingRoute).S("workspace_id") == "team-workspace", "Legacy absent routes must use the access token workspace");
        var crossed = switched.DeepClone().AsObject(); crossed["tokens"]!["access_token"] = personal["tokens"]!["access_token"]!.DeepClone();
        Rejected(crossed, "A different access token workspace must be rejected");
        var otherUser = switched.DeepClone().AsObject(); otherUser["tokens"]!["access_token"] = Token("team-workspace", "team", "another-user");
        Rejected(otherUser, "A different account subject must be rejected even inside the same team");
        var unproven = switched.DeepClone().AsObject(); unproven["tokens"]!["access_token"] = "opaque-access-token";
        Rejected(unproven, "A stale ID token requires proof of the selected route and user");
        var legacy = personal.DeepClone().AsObject(); legacy["tokens"]!["access_token"] = "opaque-access-token";
        Check(CodexAccountStore.Identity(legacy).S("plan_type") == "plus", "Aligned legacy credentials must remain supported");
        var invalid = team.DeepClone().AsObject(); invalid["tokens"]!["access_token"] = "header.invalid-payload.signature";
        Rejected(invalid, "Malformed JWT credentials must be rejected");
        var unknownPlan = switched.DeepClone().AsObject(); unknownPlan["tokens"]!["access_token"] = Token("team-workspace", null);
        var unknownIdentity = CodexAccountStore.Identity(unknownPlan);
        Check(unknownIdentity.S("plan_type").Length == 0, "An old personal subscription must not label a different workspace");
        var quota = CodexWorkspaceUsage.Normalize(JsonNode.Parse("""{"plan_type":"team","rate_limit":{"primary_window":{"used_percent":0,"limit_window_seconds":18000,"reset_at":100}}}""")!.AsObject());
        CodexWorkspaceUsage.Verify(unknownIdentity, quota);
        try { CodexWorkspaceUsage.Verify(CodexAccountStore.Identity(personal), quota); throw new Exception("Known mismatched subscriptions must be rejected"); }
        catch (CodexError) { }

        var root = Path.Combine(Path.GetTempPath(), "remotetool-workspace-identity-" + Guid.NewGuid().ToString("N"));
        try {
            var home = Path.Combine(root, "home"); var folder = Path.Combine(root, "accounts");
            var store = new CodexAccountStore(home, folder);
            var teamId = store.Capture(team); var personalId = store.Capture(personal);
            CodexJson.Atomic(Path.Combine(home, "auth.json"), switched);
            Check(store.List().S("current") == teamId, "A desktop workspace switch must select its existing team record");
            Check(store.WorkspaceAuth(teamId).ToJsonString() == original, "Current workspace usage must use the latest desktop access token");
            Check(store.WorkspaceAuth(personalId).ToJsonString() == personal.ToJsonString(), "Personal workspace usage must retain its own credentials");
            store.ValidateWorkspaceSwitch(personalId); store.Use(personalId);
            Check(CodexAccountStore.Identity(store.CurrentAuth()).S("workspace_id") == "personal-workspace", "Workspace switching must remain functional");
            Check(CodexAccountStore.Identity(store.WorkspaceAuth(teamId)).S("workspace_id") == "team-workspace", "Switching must preserve the previous workspace credentials");
        } finally { if (Directory.Exists(root)) Directory.Delete(root, true); }
        Console.WriteLine("PASS: native workspace token identity, subscription selection, credential isolation, cross-account rejection and workspace switching");
    }
}
