package com.chuckiehelper.mobile.nativeui

/** After process recreation there is no pre-send ID boundary; require unique text and a saved send time. */
internal fun restoredPendingHistoryMatch(agent: String, pending: PendingAgentSubmission, history: List<HermesMessage>): HermesMessage? {
    if (pending.timestamp <= 0) return null
    val input = agentUserMessageText(agent, pending.input).trim()
    if (input.isBlank()) return null
    return history.filter { row ->
        val time = row.timestamp
        row.role == "user" && row.serverId > 0 && time != null &&
            time >= pending.timestamp - 2000L && time <= pending.timestamp + 24 * 60 * 60 * 1000L &&
            agentUserMessageText(agent, row.text).trim() == input
    }.singleOrNull()
}
