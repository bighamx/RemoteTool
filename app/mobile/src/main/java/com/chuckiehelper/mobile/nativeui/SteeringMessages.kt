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

const val BUSY_STEERING_DELIVERY = "会话忙，待会话空闲后可重发"

fun steeringFailureDelivery(error: Exception): String =
    if (writeWasRejected(error) && error is ApiRequestFailure &&
        listOf("正在桌面端", "正在另一端", "正在运行", "已有手机任务", "占用")
            .any { it in error.message.orEmpty() })
        BUSY_STEERING_DELIVERY
    else if (writeWasRejected(error)) "发送失败" else "发送状态待核对"

fun rejectedSteering(message: SteeringMessage): Boolean = message.delivery in setOf("发送失败", BUSY_STEERING_DELIVERY)

fun steeringAppearsInHistory(message: SteeringMessage, history: List<HermesMessage>): Boolean =
    message.delivery != "发送失败" && message.delivery != BUSY_STEERING_DELIVERY && history.any {
        it.role == "user" && it.serverId > 0 && it.serverId !in message.existingIds &&
            (it.requestKey == message.key || message.delivery == "已送达" && it.requestKey == null && message.timestamp != null &&
                it.timestamp?.let { at -> at >= message.timestamp - 2000L && at <= message.timestamp + 120_000L } == true) &&
            (message.attachments.isEmpty() || it.attachments.map { file -> file.optString("id") }
                .containsAll(message.attachments.map { file -> file.optString("id") })) &&
            it.text.replace("\r\n", "\n").trim() == message.text.replace("\r\n", "\n").trim()
    }

fun pendingSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): List<SteeringMessage> {
    return reconcileSteeringMessages(history, steering).first
}

/** 发送失败的记录超过 10 分钟即视为放弃：不再重放（失败重试应由用户主动操作，而不是每次进会话重发旧文）。 */
fun isAbandonedSteering(message: SteeringMessage, now: Long = System.currentTimeMillis()): Boolean =
    message.delivery in setOf("发送失败", BUSY_STEERING_DELIVERY) && message.timestamp != null && now - message.timestamp > 10 * 60 * 1000L

fun reconcileSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): Pair<List<SteeringMessage>, List<Pair<SteeringMessage, HermesMessage>>> {
    val consumed = mutableSetOf<Long>()
    val acknowledged = mutableListOf<Pair<SteeringMessage, HermesMessage>>()
    val pending = steering.filter { message ->
        // Match first so real messages always receive their attachments. Codex IDs
        // are hashes: numeric ID order cannot prove that a record left the window.
        val match = history.filter { it.serverId !in consumed && steeringAppearsInHistory(message, listOf(it)) }.singleOrNull()
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
        if (rejectedSteering(message)) return@forEach
        if (outsideSteeringWindow(history, message)) return@forEach
        if (rows.any { it.localKey == message.key }) return@forEach
        val merged = insertLocalMessage(rows,
            HermesMessage("user", message.text, attachments = message.attachments, localKey = message.key, delivery = message.delivery, timestamp = message.timestamp),
            message.existingIds, message.anchor)
        rows.clear(); rows.addAll(merged)
    }
    return rows
}
