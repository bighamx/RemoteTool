using System.Text.Json.Nodes;
using static ChuckieHelper.WebApi.Services.Codex.CodexJson;

namespace ChuckieHelper.WebApi.Services.Codex;

internal static class CodexRunRecovery
{
    public static bool Recover(JsonObject state, bool tracked, string turnStatus = "") {
        if (tracked || state.S("status") is not ("started" or "submitting")) return false;
        state["status"] = turnStatus is "completed" or "interrupted" or "failed" ? turnStatus : "acceptance_unknown";
        state.Remove("approval");
        state["error_code"] = "run_tracking_lost";
        state["error"] = state.S("status") == "acceptance_unknown"
            ? "原任务的连接已结束，无法确认执行结果；请核对真实会话历史。可重新发送新消息，不会自动重发旧消息。"
            : state.S("status") == "completed" ? "" : "原任务已结束，可继续发送新消息";
        return true;
    }
}
