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

    public static bool Complete(JsonObject state, string session, JsonObject observation) {
        if (Text(state, "session_id") != session || Text(observation, "session_id") != session ||
            Text(state, "owner") != "desktop" || Text(state, "status") != "acceptance_unknown" ||
            Text(state, "error_code") != "run_tracking_lost" || Text(state, "turn_id").Length == 0 ||
            observation?["available"]?.GetValue<bool>() != true) return false;
        var status = Text(observation["turn_statuses"], Text(state, "turn_id"));
        if (status is not ("completed" or "failed" or "interrupted")) return false;
        state["status"] = status;
        state.Remove("error_code"); state.Remove("approval");
        state["error"] = status == "completed" ? "" : "原任务已结束，可继续发送新消息";
        return true;
    }
    public static void Restore(JsonObject state) {
        state["status"]="started";
        state.Remove("error"); state.Remove("error_code"); state.Remove("approval");
    }
}
