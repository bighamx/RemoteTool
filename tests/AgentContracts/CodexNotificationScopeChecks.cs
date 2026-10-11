using RemoteTool.WebApi.Services.Codex;

internal static class CodexNotificationScopeChecks {
    public static void Run() {
        var owner = new object();
        var oldOwner = new object();
        if (!CodexNotificationScope.Accept(owner, owner, "turn-new", "turn-new"))
            throw new Exception("Current connection and turn should be accepted");
        if (CodexNotificationScope.Accept(owner, oldOwner, "turn-new", "turn-new"))
            throw new Exception("A retired RPC must not mutate its replacement's run");
        if (CodexNotificationScope.Accept(owner, owner, "turn-new", "turn-old"))
            throw new Exception("An old turn must not mutate the new run");
        if (CodexNotificationScope.AcceptItem("turn-new", "", "turn-old", false) ||
            CodexNotificationScope.AcceptItem("turn-new", "", "", false))
            throw new Exception("A turnless old or unidentified item update must not mutate the new run");
        if (!CodexNotificationScope.AcceptItem("turn-new", "", "turn-new", false) ||
            !CodexNotificationScope.AcceptItem("turn-new", "", "", true))
            throw new Exception("Current item updates and starts should remain available");
        Console.WriteLine("Codex notification ownership checks passed");
    }
}
