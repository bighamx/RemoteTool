package com.chuckiehelper.mobile.nativeui

data class SteeringMessage(
    val key: String,
    val session: String,
    val text: String,
    val existingIds: Set<Long>,
    val anchor: Long,
    val delivery: String = "正在发送",
    val timestamp: Long? = null,
    val attachments: List<org.json.JSONObject> = emptyList(),
)

fun steeringAppearsInHistory(message: SteeringMessage, history: List<HermesMessage>): Boolean =
    message.delivery != "发送失败" && history.any {
        it.role == "user" && it.serverId > 0 && it.serverId !in message.existingIds &&
            it.text.replace("\r\n", "\n").trim() == message.text.replace("\r\n", "\n").trim()
    }

fun pendingSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): List<SteeringMessage> {
    return reconcileSteeringMessages(history, steering).first
}

/** 发送失败的记录超过 10 分钟即视为放弃：不再重放（失败重试应由用户主动操作，而不是每次进会话重发旧文）。 */
fun isAbandonedSteering(message: SteeringMessage, now: Long = System.currentTimeMillis()): Boolean =
    message.delivery == "发送失败" && message.timestamp != null && now - message.timestamp > 10 * 60 * 1000L

fun reconcileSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): Pair<List<SteeringMessage>, List<Pair<SteeringMessage, HermesMessage>>> {
    val consumed = mutableSetOf<Long>()
    val acknowledged = mutableListOf<Pair<SteeringMessage, HermesMessage>>()
    val pending = steering.filter { message ->
        // Match first so real messages always receive their attachments. Codex IDs
        // are hashes: numeric ID order cannot prove that a record left the window.
        val match = history.firstOrNull { it.serverId !in consumed && steeringAppearsInHistory(message, listOf(it)) }
        if (match != null) {
            consumed += match.serverId; acknowledged += message to match; false
        } else if (message.attachments.isEmpty() && outsideSteeringWindow(history, message)) {
            false
        } else true
    }
    return pending to acknowledged
}

private fun outsideSteeringWindow(history: List<HermesMessage>, message: SteeringMessage): Boolean {
    // A short response is not evidence of a truncated history window. Codex IDs are hashes.
    if (history.size < 500 || message.delivery != "已送达" || message.timestamp == null) return false
    val oldest = history.mapNotNull { it.timestamp }.minOrNull() ?: return false
    return message.timestamp < oldest
}

fun mergeSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): List<HermesMessage> {
    val rows = history.toMutableList()
    pendingSteeringMessages(history, steering).forEach { message ->
        if (outsideSteeringWindow(history, message)) return@forEach
        if (rows.any { it.localKey == message.key }) return@forEach
        val anchor = rows.indexOfLast { it.serverId == message.anchor && it.serverId > 0 }
        var index = if (anchor >= 0) anchor + 1 else rows.size
        while (index < rows.size && rows[index].localKey != null) index++
        rows.add(index, HermesMessage("user", message.text, attachments = message.attachments, localKey = message.key, delivery = message.delivery, timestamp = message.timestamp))
    }
    return rows
}
