package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** A saved run ID locates a task; only a recent server observation proves activity. */
internal data class SessionActivityEvidence(val state: String, val observedAt: Long) {
    fun label(now: Long): String? = when {
        state in setOf("active", "waiting", "submitting") && now - observedAt > 30_000 -> "状态未更新"
        state == "waiting" -> "等待确认"
        state == "submitting" -> "正在提交"
        state == "active" -> "运行中"
        else -> null
    }
}

internal fun sessionActivityEvidence(row: JSONObject, observedAt: Long): SessionActivityEvidence {
    val status = row.optJSONObject("status")
    val type = status?.optString("type") ?: row.optString("status")
    val flags = status?.array("activeFlags")?.let { values -> (0 until values.length()).map { values.optString(it) } }.orEmpty()
    return SessionActivityEvidence(when {
        type.isBlank() || type == "null" -> "unknown"
        type !in setOf("active", "running", "started", "in_progress", "waiting_for_approval") -> "idle"
        type == "waiting_for_approval" || flags.any { it in setOf("waitingOnApproval", "waitingOnUserInput") } -> "waiting"
        else -> "active"
    }, observedAt)
}

internal fun runActivityEvidence(row: JSONObject, observedAt: Long): SessionActivityEvidence =
    SessionActivityEvidence(when (row.optString("status")) {
        "started", "running", "in_progress", "stopping", "queued" -> if (row.optJSONObject("approval") != null) "waiting" else "active"
        "waiting_for_approval" -> "waiting"
        "submitting" -> "submitting"
        else -> "idle" // Completed, failed, missing, or uncertain tracking cannot prove a running task.
    }, observedAt)

internal fun updateSessionActivity(
    previous: Map<String, SessionActivityEvidence>, session: String, next: SessionActivityEvidence,
): Map<String, SessionActivityEvidence> =
    if ((previous[session]?.observedAt ?: Long.MIN_VALUE) > next.observedAt) previous else previous + (session to next)

internal fun sessionActivityLabel(evidence: SessionActivityEvidence?, now: Long, pending: Boolean): String? =
    evidence?.label(now) ?: if (pending) "待核对" else null
