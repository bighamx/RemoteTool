package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Local UI evidence of a successful operation, never text submitted to the agent. */
data class AgentCompactionNotice(val run: String, val session: String, val timestamp: Long, val afterMessageId: Long = 0,
    val compactionId: String? = null, val startedAt: Long? = null) {
    fun json() = obj("run" to run, "session" to session, "timestamp" to timestamp, "afterMessageId" to afterMessageId,
        "compactionId" to compactionId, "startedAt" to startedAt)

    companion object {
        fun restore(row: JSONObject): AgentCompactionNotice? {
            val run = row.optString("run").takeIf { it.isNotBlank() && it != "null" } ?: return null
            val session = row.optString("session").takeIf { it.isNotBlank() && it != "null" } ?: return null
            val time = parseMessageTimestamp(row.opt("timestamp")) ?: return null
            return AgentCompactionNotice(run, session, time, row.optLong("afterMessageId"),
                row.optString("compactionId").takeIf { it.isNotBlank() && it != "null" }
                    ?: run.takeIf { it.startsWith("external-") }?.removePrefix("external-"),
                parseMessageTimestamp(row.opt("startedAt")))
        }
    }
}

/** One operation can be observed through both the task endpoint and the native rollout. */
internal fun coalesceCompactionNotices(notices: List<AgentCompactionNotice>): List<AgentCompactionNotice> {
    val rows = mutableListOf<AgentCompactionNotice>()
    for (incoming in notices) {
        val incomingId = incoming.compactionId ?: incoming.run.takeIf { it.startsWith("external-") }?.removePrefix("external-")
        val index = rows.indexOfFirst { previous ->
            val previousId = previous.compactionId ?: previous.run.takeIf { it.startsWith("external-") }?.removePrefix("external-")
            previous.session == incoming.session && (previous.run == incoming.run ||
                incomingId != null && incomingId == previousId ||
                // Only pair different observation sources. Two known native IDs are
                // two operations, even if their completion times are very close.
                (incomingId == null || previousId == null) && previous.run.startsWith("external-") != incoming.run.startsWith("external-") &&
                (kotlin.math.abs(previous.timestamp - incoming.timestamp) < 5_000 ||
                    (if (incoming.run.startsWith("external-")) previous.startedAt?.let { incoming.timestamp in it..previous.timestamp }
                    else incoming.startedAt?.let { previous.timestamp in it..incoming.timestamp }) == true))
        }
        if (index < 0) rows += incoming.copy(compactionId = incomingId)
        else {
            val previous = rows[index]
            val native = when {
                incoming.run.startsWith("external-") -> incoming
                previous.run.startsWith("external-") -> previous
                incomingId != null -> incoming
                else -> previous
            }
            rows[index] = previous.copy(compactionId = incomingId ?: previous.compactionId,
                timestamp = native.timestamp, startedAt = previous.startedAt ?: incoming.startedAt,
                afterMessageId = native.afterMessageId.takeIf { it > 0 } ?: previous.afterMessageId)
        }
    }
    return rows
}

fun successfulCompaction(run: String, result: JSONObject): Boolean =
    result.optString("status") == "completed" && (result.optString("kind") == "compact" || run.startsWith("hcompact_"))

/** Codex returns native compaction items in history order; discard legacy local overlays. */
internal fun mergeAgentCompactionNotices(agent: String, history: List<HermesMessage>, notices: List<AgentCompactionNotice>): List<HermesMessage> =
    if (agent == "codex") history.filterNot { it.localKey?.startsWith("compaction-") == true }
    else mergeCompactionNotices(history, notices)

fun mergeCompactionNotices(history: List<HermesMessage>, notices: List<AgentCompactionNotice>): List<HermesMessage> {
    val rows = history.filterNot { it.localKey?.startsWith("compaction-") == true }
    val insertions = mutableMapOf<Int, MutableList<HermesMessage>>()
    for (notice in coalesceCompactionNotices(notices).sortedBy { it.timestamp }) {
        // Resolve against original message boundaries only. Previously inserted
        // notices must not make an ambiguous undated gap look timestamped.
        val future = rows.indexOfFirst { it.timestamp?.let { time -> time > notice.timestamp } == true }
        val index = if (future >= 0) future else rows.size
        val previousTime = rows.getOrNull(index - 1)?.timestamp ?: continue
        if (previousTime > notice.timestamp) continue
        // Legacy afterMessageId values may refer to a later undated snapshot.
        // Neither an ID nor the earliest date proves this operation belongs in
        // a gap; require a dated immediate predecessor at the actual slot.
        insertions.getOrPut(index) { mutableListOf() }.add(
            HermesMessage("system", "上下文压缩完成", localKey = "compaction-${notice.run}", timestamp = notice.timestamp))
    }
    return buildList {
        rows.forEachIndexed { index, message -> addAll(insertions[index].orEmpty()); add(message) }
        addAll(insertions[rows.size].orEmpty())
    }
}
