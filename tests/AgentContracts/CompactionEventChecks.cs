using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services.Codex;

internal static class CompactionEventChecks
{
    internal static void Run()
    {
        var item = new JsonObject { ["type"] = "contextCompaction", ["id"] = "native-compaction" };
        if (CodexCompactionEvent.Create(item, false)?["event"]?.ToString() != "context.compaction.started") throw new Exception("Native compaction must not be a tool");
        var completed = CodexCompactionEvent.Create(item, true);
        if (completed?["event"]?.ToString() != "context.compaction.completed" || completed["item_id"]?.ToString() != "native-compaction") throw new Exception("Completion must signal a native history refresh");
        item["status"] = "failed";
        if (CodexCompactionEvent.Create(item, true)?["event"]?.ToString() != "context.compaction.failed") throw new Exception("Failed compaction must not announce completion");
        if (CodexCompactionEvent.Create(new JsonObject { ["type"] = "commandExecution" }, true) != null) throw new Exception("Actual tools keep their own events");
        Console.WriteLine("4 compaction event contracts passed");
    }
}
