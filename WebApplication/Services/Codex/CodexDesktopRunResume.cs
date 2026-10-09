using System.Text.Json.Nodes;

namespace RemoteTool.WebApi.Services.Codex;

internal static class CodexDesktopRunResume
{
    private static string Text(JsonNode node,string key)=>node?[key]?.ToString()??"";
    public static bool CanResume(JsonObject state,string session,JsonObject observation) =>
        Text(state,"session_id")==session && Text(observation,"session_id")==session &&
        Text(state,"owner")=="desktop" && Text(state,"status")=="acceptance_unknown" &&
        Text(state,"error_code")=="run_tracking_lost" && Text(state,"kind")!="compact" &&
        Text(state,"turn_id").Length>0 && Text(state,"turn_id")==Text(observation,"activity_id") &&
        observation?["available"]?.GetValue<bool>()==true && observation?["running"]?.GetValue<bool>()==true;

    public static void Restore(JsonObject state) {
        state["status"]="started";
        state.Remove("error"); state.Remove("error_code"); state.Remove("approval");
    }
}
