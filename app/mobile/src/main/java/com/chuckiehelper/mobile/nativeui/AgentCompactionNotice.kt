package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Local UI evidence of a successful operation, never text submitted to the agent. */
data class AgentCompactionNotice(val run: String, val session: String, val timestamp: Long, val afterMessageId: Long = 0) {
    fun json() = obj("run" to run, "session" to session, "timestamp" to timestamp, "afterMessageId" to afterMessageId)

    companion object {
        fun restore(row: JSONObject): AgentCompactionNotice? {
            val run = row.optString("run").takeIf { it.isNotBlank() && it != "null" } ?: return null
            val session = row.optString("session").takeIf { it.isNotBlank() && it != "null" } ?: return null
            val time = parseMessageTimestamp(row.opt("timestamp")) ?: return null
            return AgentCompactionNotice(run, session, time, row.optLong("afterMessageId"))
        }
    }
}

fun successfulCompaction(run: String, result: JSONObject): Boolean =
    result.optString("status") == "completed" && (result.optString("kind") == "compact" || run.startsWith("hcompact_"))

fun mergeCompactionNotices(history: List<HermesMessage>, notices: List<AgentCompactionNotice>): List<HermesMessage> {
    val rows = history.filterNot { it.localKey?.startsWith("compaction-") == true }.toMutableList()
    for (notice in notices.distinctBy { it.run }.sortedBy { it.timestamp }) {
        val future = rows.indexOfFirst { it.timestamp?.let { time -> time > notice.timestamp } == true }
        val anchor = rows.indexOfLast { it.serverId > 0 && it.serverId == notice.afterMessageId }
        // IDs are identities, never chronological counters. Retain a known boundary if
        // native history omits timestamps; do not move an older off-window notice to the tail.
        val index = when {
            future >= 0 && future > anchor -> future
            anchor >= 0 -> {
                var next = anchor + 1
                while (next < rows.size && rows[next].role == "system" &&
                    rows[next].timestamp?.let { it <= notice.timestamp } == true) next++
                next
            }
            rows.isEmpty() -> 0
            history.size >= 500 -> continue
            rows.all { it.timestamp != null && it.timestamp <= notice.timestamp } -> rows.size
            else -> continue
        }
        rows.add(index, HermesMessage("system", "上下文压缩完成", localKey = "compaction-${notice.run}", timestamp = notice.timestamp))
    }
    return rows
}
