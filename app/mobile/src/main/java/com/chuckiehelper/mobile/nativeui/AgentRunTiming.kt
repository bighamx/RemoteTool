package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Per-run state: neither polling nor replaying an SSE stream is a model response. */
data class AgentRunTiming(
    val startedAt: Long? = null,
    val lastResponseAt: Long? = null,
    val sequence: Long = -1,
    val lastUnsequencedEvent: String? = null,
) {
    fun event(event: JSONObject, receivedAt: Long): AgentRunTiming {
        val n = event.optLong("seq", -1)
        if (n >= 0 && n <= sequence) return this
        val identity = if (n < 0) event.toString() else null
        if (identity != null && identity == lastUnsequencedEvent) return this
        val type = event.optString("type", event.optString("event"))
        val response = type.startsWith("tool.") || type.startsWith("reasoning.") ||
            type in setOf("message.delta", "message.interim", "approval.request", "response.output_text.delta")
        val eventTime = parseMessageTimestamp(event.opt("timestamp"))?.coerceAtMost(receivedAt) ?: receivedAt
        return copy(
            lastResponseAt = if (response) maxOf(lastResponseAt ?: 0, eventTime) else lastResponseAt,
            sequence = maxOf(sequence, n),
            lastUnsequencedEvent = identity ?: lastUnsequencedEvent,
        )
    }

    fun json() = obj("startedAt" to startedAt, "lastResponseAt" to lastResponseAt,
        "sequence" to sequence, "lastUnsequencedEvent" to lastUnsequencedEvent)

    companion object {
        fun restore(row: JSONObject) = AgentRunTiming(
            parseMessageTimestamp(row.opt("startedAt")), parseMessageTimestamp(row.opt("lastResponseAt")),
            row.optLong("sequence", -1), row.optString("lastUnsequencedEvent").takeIf { it.isNotBlank() && it != "null" },
        )
    }
}

fun formatRunElapsed(since: Long?, now: Long): String {
    if (since == null) return "—"
    val seconds = ((now - since).coerceAtLeast(0) / 1000)
    return if (seconds < 3600) "%02d:%02d".format(seconds / 60, seconds % 60)
    else "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
}
