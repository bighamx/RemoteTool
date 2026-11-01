using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;
internal static class CodexSessionPermissionsChecks {
    public static void Run() {
        var path = Path.Combine(Path.GetTempPath(), Guid.NewGuid()+".json");
        try {
            var start = new JsonObject(); CodexSessionPermissions.ApplyStart("full", start);
            if (start["sandbox"]?.ToString() != "danger-full-access" || start["approvalPolicy"]?.ToString() != "never") throw new Exception("Full access requires sandbox and approval policy");
            var policy = new CodexSessionPermissions(path); policy.Set("remote-new", "full");
            var restored = new CodexSessionPermissions(path); var resume = new JsonObject(); restored.ApplyResume("remote-new",resume);
            if (resume.ToJsonString() != start.ToJsonString()) throw new Exception("Full access must survive restart and resume");
            restored.Set("remote-new", "workspace");
            var restricted = new JsonObject(); restored.ApplyResume("remote-new", restricted);
            if (restricted["sandbox"]?.ToString() != "workspace-write" || restricted["approvalPolicy"]?.ToString() != "on-request") throw new Exception("An explicit permission change must replace the new-session default");
            var existing = new JsonObject(); restored.ApplyResume("existing",existing);
            if (existing.Count != 0) throw new Exception("Existing sessions must retain their policies");
            Console.WriteLine("Session permissions: create, restart/resume and existing-session isolation passed");
        } finally { if(File.Exists(path))File.Delete(path); }
    }
}
