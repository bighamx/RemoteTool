package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

fun jobStateLabel(state: String) = when (state.lowercase()) {
    "processing" -> "执行中"; "enqueued" -> "排队中"; "scheduled" -> "等待执行"
    "succeeded" -> "已完成"; "failed" -> "执行失败"; "deleted" -> "已删除"; "awaiting" -> "等待前置任务"
    else -> state.ifBlank { "状态未知" }
}
fun jobActive(state: String) = state.lowercase() in setOf("processing", "enqueued", "scheduled", "awaiting")
fun jobCurrentState(detail: JSONObject): String = detail.optString("state").takeUnless { it.isBlank() || it == "null" }
    ?: detail.array("history").objects().lastOrNull()?.optString("stateName").orEmpty()
fun jobExecutionFinishedAt(detail: JSONObject): Long? {
    val history = detail.array("history").objects()
    val start = history.indexOfLast { it.optString("stateName") == "Processing" }
    return if (start < 0) null else history.drop(start + 1).firstOrNull { it.optString("stateName") != "Processing" }
        ?.optString("createdAt")?.let { parseMessageTimestamp(it) }
}

data class JobConsoleBuffer(val attempt: String? = null, val nextOffset: Int = 0, val total: Int = 0,
    val hasMore: Boolean = false, val lines: List<JSONObject> = emptyList()) {
    fun merge(page: JSONObject, older: Boolean = false): JobConsoleBuffer {
        val incoming = page.optString("attempt").takeUnless { it.isBlank() || it == "null" }
        val fresh = page.array("data").objects()
        val previous = if (incoming == attempt && !page.optBoolean("reset")) lines else emptyList()
        val merged = (previous + fresh).associateBy { it.optInt("index") }.values.sortedBy { it.optInt("index") }
        return JobConsoleBuffer(incoming, if (older && incoming == attempt) nextOffset else page.optInt("nextOffset"),
            page.optInt("total"), if (older) hasMore else page.optBoolean("hasMore"),
            if (older) merged.take(2000) else merged.takeLast(2000))
    }
}

/** Render object parameters as labelled fields instead of dumping a JSON document. */
fun jobParameterFields(value: Any?, prefix: String = "", depth: Int = 0): List<Pair<String, String>> {
    if (value is JSONObject && depth < 3) return value.keys().asSequence().take(40).flatMap { key ->
        jobParameterFields(value.opt(key), if (prefix.isEmpty()) key else "$prefix.$key", depth + 1).asSequence()
    }.toList()
    val display = when (value) {
        null, JSONObject.NULL -> "未设置"
        is org.json.JSONArray -> (0 until value.length()).take(20).joinToString("、") { value.opt(it).toString() }
        else -> value.toString()
    }
    return listOf(prefix to display)
}
