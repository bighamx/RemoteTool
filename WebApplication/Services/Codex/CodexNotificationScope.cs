namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexNotificationScope {
    public static bool Accept(object expectedOwner, object origin, string expectedTurn, string incomingTurn) =>
        ReferenceEquals(expectedOwner, origin) &&
        (string.IsNullOrEmpty(incomingTurn) || string.IsNullOrEmpty(expectedTurn) || incomingTurn == expectedTurn);

    public static bool AcceptItem(string expectedTurn, string incomingTurn, string knownItemTurn, bool startsItem) =>
        string.IsNullOrEmpty(expectedTurn) ||
        !string.IsNullOrEmpty(incomingTurn) && incomingTurn == expectedTurn ||
        string.IsNullOrEmpty(incomingTurn) &&
            (!string.IsNullOrEmpty(knownItemTurn) ? knownItemTurn == expectedTurn : startsItem);
}
