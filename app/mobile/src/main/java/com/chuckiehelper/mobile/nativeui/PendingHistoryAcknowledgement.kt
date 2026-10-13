package com.chuckiehelper.mobile.nativeui

/** IDs can include synthetic tool-summary hashes. Only the captured set is an append boundary. */
internal fun currentSubmissionHistoryMatch(agent: String, pending: PendingAgentSubmission, existing: Set<Long>, history: List<HermesMessage>): HermesMessage? {
    if (agent == "codex") return history.singleOrNull { it.role == "user" && it.serverId > 0 && it.requestKey == pending.key }
    // Hermes native rows use SQLite sequence IDs. Tool summaries use large hashes
    // and cannot establish a send boundary.
    val lastNativeId = existing.filter { it > 0 && it < 1_000_000_000_000L }.maxOrNull() ?: 0L
    return history.filter { row -> row.role == "user" && row.serverId > 0 && row.serverId !in existing &&
        row.serverId > lastNativeId &&
        row.timestamp?.let { it >= pending.timestamp - 2000L && it <= pending.timestamp + 120_000L } == true &&
        (pending.attachmentIds.isEmpty() || row.attachments.map { it.optString("id") }.containsAll(pending.attachmentIds)) &&
        agentUserMessageText(agent, row.text).trim() == agentUserMessageText(agent, pending.input).trim() }
        .singleOrNull()
}

/** After process recreation, only a native request key can prove the message identity. */
internal fun restoredPendingHistoryMatch(agent: String, pending: PendingAgentSubmission, history: List<HermesMessage>): HermesMessage? {
    if (agent != "codex") return null
    return history.singleOrNull { it.role == "user" && it.serverId > 0 && it.requestKey == pending.key }
}
