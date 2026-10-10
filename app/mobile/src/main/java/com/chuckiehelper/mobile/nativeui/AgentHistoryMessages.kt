package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

internal fun agentHistoryMessage(agent: String, row: JSONObject): HermesMessage? {
    val role = row.optString("role")
    val content = row.optString("content").takeUnless { it == "null" }.orEmpty()
    if (role == "system" && row.optString("type") == "contextCompaction") {
        return HermesMessage("system", content.ifBlank { "上下文压缩" }, row.optLong("id"),
            timestamp = parseMessageTimestamp(row.opt("timestamp")), editable = false)
    }
    val description = if (agent == "hermes" && role == "assistant" && content.isBlank()) historyToolNarration(row) else null
    val text = if (role == "user") agentUserMessageText(agent, content) else description ?: content
    val attachments = row.array("attachments").objects()
    if (!visibleAgentMessage(role, text, attachments.size)) return null
    return HermesMessage(role, text, row.optLong("id"), attachments,
        timestamp = parseMessageTimestamp(row.opt("timestamp")) ?: parseMessageTimestamp(row.opt("created_at")),
        narration = description != null || isAssistantNarration(row),
        nativeTurnId = row.optString("turn_id").takeIf { it.isNotBlank() && it != "null" },
        editable = row.optBoolean("editable", true))
}
