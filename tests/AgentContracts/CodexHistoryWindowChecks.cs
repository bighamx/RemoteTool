using System.Text.Json.Nodes;
using RemoteTool.WebApi.Services;
using RemoteTool.WebApi.Services.Codex;
using static RemoteTool.WebApi.Services.Codex.CodexJson;

internal static class CodexHistoryWindowChecks
{
    public static async Task Run() {
        static void Check(bool ok, string name) { if (!ok) throw new Exception(name); }
        var request = CodexHistoryWindow.Request("session", "cursor");
        Check(request.S("itemsView") == "full" && request.S("sortDirection") == "desc" && request.S("cursor") == "cursor", "history explicitly requests complete native items");
        var items = new JsonArray(Obj(("id", "u1"), ("type", "userMessage"), ("content", new JsonArray(Obj(("type", "text"), ("text", "initial"))))),
            Obj(("id", "a1"), ("type", "agentMessage"), ("text", "progress"), ("phase", "commentary")));
        for (var i = 0; i < 100; i++) items.Add(Obj(("id", "tool" + i), ("type", "commandExecution")));
        items.Add(Obj(("id", "u2"), ("type", "userMessage"), ("content", new JsonArray(Obj(("type", "text"), ("text", "steering"))))));
        items.Add(Obj(("id", "a2"), ("type", "agentMessage"), ("text", "final")));
        var turn = Obj(("id", "turn"), ("items", items));
        var budget = new CodexHistoryWindow(5); budget.Add(turn);
        Check(!budget.Done, "100 tools do not consume a five-message budget");
        budget.Add(Obj(("id", "previous"), ("items", new JsonArray(Obj(("id", "old"), ("type", "agentMessage"), ("text", "older"))))));
        Check(budget.Done, "initial user, steering and commentary each count as a message");
        var from = new CodexHistoryWindow(1, fromId: CodexHistoryWindow.MessageId("u1").ToString()); from.Add(turn);
        Check(from.Done, "expanded history reaches requested native anchor");
        var older = new CodexHistoryWindow(2, olderBefore: CodexHistoryWindow.MessageId("u2").ToString()); older.Add(turn);
        Check(older.Done, "older pagination counts only messages before anchor");
        var document = Obj(("data", new JsonArray(Obj(("id", 1), ("role", "user"), ("content", "kept")))), ("has_more", true));
        AgentHistoryWindow.Select(document, 100);
        Check(document.B("has_more"), "controller preserves upstream indication of unloaded older pages");
        var never = new TaskCompletionSource<JsonObject?>();
        var partial = await AgentOverview.ReadPart(_ => never.Task, CancellationToken.None, TimeSpan.FromMilliseconds(30));
        Check(partial == null, "stalled optional overview part cannot hold complete history hostage");
        using var cancelled = new CancellationTokenSource(); cancelled.Cancel();
        try { await AgentOverview.ReadPart(_ => never.Task, cancelled.Token); throw new Exception("request cancellation swallowed"); }
        catch (OperationCanceledException) { }
        Console.WriteLine("Codex history window checks passed: full items, visible-message budget, anchors, older pages, bounded overview");
    }
}
