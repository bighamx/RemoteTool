package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

internal fun externalActivityRunning(snapshot: JSONObject?, session: String?, now: Long, verifiedAt: Long): Boolean =
    snapshot != null && snapshot.optBoolean("available") && snapshot.optBoolean("running") &&
        snapshot.optString("session_id") == session && now - verifiedAt <= 30_000

internal fun externalActivityTiming(snapshot: JSONObject?) = AgentRunTiming(
    startedAt = parseMessageTimestamp(snapshot?.opt("started_at")),
    lastResponseAt = parseMessageTimestamp(snapshot?.opt("last_response_at")),
)

internal fun externalActivityEvents(snapshot: JSONObject?): List<HermesEvent> {
    if (snapshot == null) return emptyList()
    val tools = snapshot.array("progress").objects().map {
        HermesEvent(when (it.optString("status")) { "completed" -> "完成"; "failed" -> "失败"; else -> "执行中" },
            it.optString("tool"), it.optString("preview"))
    }
    val description = snapshot.optString("activity_text").takeIf { it.isNotBlank() && it != "null" }
    return if (description == null) tools else tools + HermesEvent("进度", description)
}
