package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

internal fun externalActivityRunning(snapshot: JSONObject?, session: String?, now: Long, verifiedAt: Long): Boolean =
    snapshot != null && snapshot.optBoolean("available") && snapshot.optBoolean("running") &&
        snapshot.optString("session_id") == session && now - verifiedAt <= 30_000

/** Hermes persists these exact live phase descriptions; terminal stamps must not match. */
internal fun externalActivityCompacting(snapshot: JSONObject?): Boolean =
    snapshot?.optString("kind") == "compact" ||
        (snapshot?.optBoolean("available") == true && snapshot.optBoolean("running") &&
            snapshot.optString("activity_text") in setOf("context compression started", "context compression in progress"))

internal fun externalActivityTiming(snapshot: JSONObject?) = AgentRunTiming(
    startedAt = parseMessageTimestamp(snapshot?.opt(if (externalActivityCompacting(snapshot)) "phase_started_at" else "started_at")),
    lastResponseAt = parseMessageTimestamp(snapshot?.opt("last_response_at"))?.takeIf {
        !externalActivityCompacting(snapshot) || parseMessageTimestamp(snapshot?.opt("phase_started_at"))?.let { start -> it >= start } == true
    },
)

internal fun externalActivityLabel(snapshot: JSONObject?) =
    if (externalActivityCompacting(snapshot)) "正在压缩上下文" else "执行中"

internal fun externalActivityEvents(snapshot: JSONObject?): List<HermesEvent> {
    if (snapshot == null) return emptyList()
    val tools = snapshot.array("progress").objects().map {
        HermesEvent(when (it.optString("status")) { "completed" -> "完成"; "failed" -> "失败"; else -> "执行中" },
            it.optString("tool"), it.optString("preview"))
    }
    val description = snapshot.optString("activity_text").takeIf { it.isNotBlank() && it != "null" }
    return if (description == null) tools else tools + HermesEvent("进度", description)
}
