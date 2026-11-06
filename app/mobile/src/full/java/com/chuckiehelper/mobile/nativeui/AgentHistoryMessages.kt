package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

internal fun agentHistoryMessage(agent: String, row: JSONObject): HermesMessage? {
    val role = row.optString("role")
    val attachments = row.array("attachments").objects()
    val uid = if (agent == "hermes") row.cleanSetting("message_uid").takeIf { it.isNotBlank() } else null
    val rawContent = if (agent == "hermes") agentMessageContentText(row.opt("content"), attachments.isNotEmpty())
        else row.optString("content").takeUnless { it == "null" }.orEmpty()
    val content = if (agent == "hermes" && role == "assistant") hermesAssistantText(rawContent) else rawContent
    if (role == "system" && row.optString("type") == "interaction") {
        val entries = row.optJSONArray("interactions")?.objects().orEmpty()
        if (entries.isEmpty()) return null
        return HermesMessage("system", content, row.optLong("id"),
            timestamp = parseMessageTimestamp(row.opt("timestamp")), editable = false,
            nativeTurnId = row.cleanSetting("turn_id").takeIf { it.isNotBlank() }, interactions = entries, messageType = "interaction", messageUid = uid)
    }
    if (role == "system" && row.optString("type") in setOf("contextCompaction", "toolSummary")) {
        return HermesMessage("system", content.ifBlank { "上下文压缩" }, row.optLong("id"),
            timestamp = parseMessageTimestamp(row.opt("timestamp")), editable = false,
            nativeTurnId = row.cleanSetting("turn_id").takeIf { it.isNotBlank() }, messageType = row.optString("type"), messageUid = uid)
    }
    val description = if (agent == "hermes" && role == "assistant" && rawContent.isBlank()) historyToolNarration(row) else null
    val text = if (role == "user") agentUserMessageText(agent, content, attachments.isNotEmpty()) else description ?: content
    if (!visibleAgentMessage(role, text, attachments.size)) return null
    return HermesMessage(role, text, row.optLong("id"), attachments,
        timestamp = parseMessageTimestamp(row.opt("timestamp")) ?: parseMessageTimestamp(row.opt("created_at")),
        narration = description != null || isAssistantNarration(row),
        nativeTurnId = row.optString("turn_id").takeIf { it.isNotBlank() && it != "null" },
        editable = row.optBoolean("editable", true),
        requestKey = row.optString("client_request_id").takeIf { it.isNotBlank() && it != "null" },
        messageUid = uid)
}
