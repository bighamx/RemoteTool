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

fun mergeCompactionNotices(history: List<HermesMessage>, notices: List<AgentCompactionNotice>): List<HermesMessage> {
    val rows = history.filterNot { it.localKey?.startsWith("compaction-") == true }.toMutableList()
    // A partial/compacted snapshot can contain an undated prefix. Its IDs alone do
    // not prove that a cached operation belongs there; never backfill old notices
    // next to a recent undated user message just because an old anchor survived.
    val earliestTime = rows.mapNotNull { it.timestamp }.minOrNull()
    for (notice in coalesceCompactionNotices(notices).sortedBy { it.timestamp }) {
        if (earliestTime != null && notice.timestamp < earliestTime) continue
        val future = rows.indexOfFirst { it.timestamp?.let { time -> time > notice.timestamp } == true }
        val anchor = rows.indexOfLast { it.serverId > 0 && it.serverId == notice.afterMessageId &&
            (it.timestamp == null || it.timestamp <= notice.timestamp) }
        // IDs are identities, never chronological counters. Retain a known boundary if
        // native history omits timestamps; do not move an older off-window notice to the tail.
        val index = when {
            future >= 0 -> future
            anchor >= 0 -> {
                var next = anchor + 1
                while (next < rows.size && rows[next].role == "system" &&
                    rows[next].timestamp?.let { it <= notice.timestamp } == true) next++
                next
            }
            rows.isEmpty() -> continue
            history.size >= 500 -> continue
            rows.all { it.timestamp != null && it.timestamp <= notice.timestamp } -> rows.size
            else -> continue
        }
        rows.add(index, HermesMessage("system", "上下文压缩完成", localKey = "compaction-${notice.run}", timestamp = notice.timestamp))
    }
    return rows
}
