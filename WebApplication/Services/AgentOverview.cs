using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services;

internal static class AgentOverview
{
    // Both agents' active-run endpoint can already return the complete run document.
    // Reuse only a session-owned document; a bare run_id still needs a status read.
    public static JsonObject? RunDocument(JsonObject? state, string sessionId) =>
        state?["session_id"]?.ToString() == sessionId &&
        !string.IsNullOrWhiteSpace(state["run_id"]?.ToString()) &&
        !string.IsNullOrWhiteSpace(state["status"]?.ToString())
            ? state.DeepClone().AsObject() : null;
}
