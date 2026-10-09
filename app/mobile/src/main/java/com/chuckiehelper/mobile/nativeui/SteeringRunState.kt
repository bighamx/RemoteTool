package com.chuckiehelper.mobile.nativeui

/** GET status and POST acceptance use different vocabularies for Hermes and Codex. */
internal fun steeringRunPreflightFailure(agent: String, status: String): ApiRequestFailure? {
    if (status == if (agent == "hermes") "running" else "started") return null
    if (status in setOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown"))
        return ApiRequestFailure("原任务已结束，本次插话未写入。草稿已保留，请作为新消息发送。", 409, "run_stale", "rejected")
    val message = when (status) {
        "stopping" -> "任务正在停止，本次插话未写入。草稿已保留，请等待停止完成后发送。"
        "waiting_for_approval" -> "任务正在等待审批，本次插话未写入。草稿已保留，请处理审批后重试。"
        "started", "submitting", "queued" -> "任务尚未开始执行，本次插话未写入。草稿已保留，请稍后重试。"
        else -> "暂时无法确认任务是否接受插话，本次未写入。草稿已保留，请刷新后重试。"
    }
    return ApiRequestFailure(message, 409, "run_not_accepting_steer", "rejected")
}
