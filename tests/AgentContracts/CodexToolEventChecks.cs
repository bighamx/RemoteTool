using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

internal static class CodexToolEventChecks {
    public static void Run() {
        var item = JsonNode.Parse("""{"id":"item-a","callId":"call-a","completed":true,"command":"git status"}""")!;
        var fields = CodexToolEvent.Fields(item, "终端", "git status").ToDictionary(field => field.Key, field => field.Value);
        if (fields["item_id"]?.ToString() != "item-a" || fields["call_id"]?.ToString() != "call-a")
            throw new Exception("Desktop tool events must preserve native item and call identities");
        if (CodexToolEvent.Status(item, JsonNode.Parse("""{"status":"completed"}""")!) != "completed")
            throw new Exception("A desktop tool's completed flag must survive history reconstruction");
        var failed = JsonNode.Parse("""{"completed":true,"exitCode":1}""")!;
        if (CodexToolEvent.Status(failed, JsonNode.Parse("""{"status":"completed"}""")!) != "failed")
            throw new Exception("A nonzero exit code must remain a failure");
        Console.WriteLine("Codex tool identity and status checks passed");
    }
}
