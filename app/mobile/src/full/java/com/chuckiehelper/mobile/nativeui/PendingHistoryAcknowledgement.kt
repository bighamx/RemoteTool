package com.chuckiehelper.mobile.nativeui

/** IDs can include synthetic tool-summary hashes. Only the captured set is an append boundary. */
internal fun currentSubmissionHistoryMatch(agent: String, pending: PendingAgentSubmission, existing: Set<Long>, history: List<HermesMessage>): HermesMessage? {
    if (agent == "codex") return history.singleOrNull { it.role == "user" && it.serverId > 0 && it.requestKey == pending.key }
    // Hermes native rows use SQLite sequence IDs. Tool summaries use large hashes
    // and cannot establish a send boundary.
    val lastNativeId = existing.filter { it > 0 && it < 1_000_000_000_000L }.maxOrNull() ?: 0L
    return history.filter { row -> row.role == "user" && row.serverId > 0 && row.serverId !in existing &&
        row.serverId > lastNativeId &&
        // 下界放宽到 10s：设备时钟可能比服务器快几秒（NTP 漂移），消息不可能早于发送前 10s 入库。
        row.timestamp?.let { it >= pending.timestamp - 10_000L && it <= pending.timestamp + 120_000L } == true &&
        (pending.attachmentIds.isEmpty() || row.attachments.map { it.optString("id") }.containsAll(pending.attachmentIds)) &&
        run {
            // agentUserMessageText 已剥 OUT-OF-BAND 外壳；网关还会把 turn 活跃期间的
            // 多条消息合并进一行（一行一条），合并行按文本行匹配。
            hermesUserMessageContains(row.text, pending.input)
        } }
        .also { matched ->
            if (matched.isEmpty()) {
                // 诊断日志：确认失败时输出判据现场（Release 包也输出，量极低）。
                val near = history.filter { it.role == "user" && it.serverId > 0 }
                println("HermesAck confirm miss: pending.key=${pending.key} ts=${pending.timestamp} " +
                    "window=[${pending.timestamp - 10_000},${pending.timestamp + 120_000}] " +
                    "lastNativeId=$lastNativeId existing=${existing.size} " +
                    "recentUserRows=" + near.takeLast(3).joinToString { "id=${it.serverId},ts=${it.timestamp},text=${it.text.take(20)}" })
            }
        }
        .singleOrNull()
}

/** After process recreation, only a native request key can prove the message identity. */
internal fun restoredPendingHistoryMatch(agent: String, pending: PendingAgentSubmission, history: List<HermesMessage>): HermesMessage? {
    if (agent != "codex") return null
    return history.singleOrNull { it.role == "user" && it.serverId > 0 && it.requestKey == pending.key }
}
