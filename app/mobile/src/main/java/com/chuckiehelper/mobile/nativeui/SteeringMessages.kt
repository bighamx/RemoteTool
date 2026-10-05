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

fun reconcileSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): Pair<List<SteeringMessage>, List<Pair<SteeringMessage, HermesMessage>>> {
    val consumed = mutableSetOf<Long>()
    val acknowledged = mutableListOf<Pair<SteeringMessage, HermesMessage>>()
    // 历史窗口可能裁掉插话对应的真实记录（默认页 500 条）——插话发出时它在服务端，
    // 现在只是滑出窗口。两种判据（旧记录无 timestamp，用 anchor；新记录用 timestamp）：
    //   anchor < 窗口最小 serverId —— anchor 是发送时最后一条真实消息 id，已滑出窗口 ⇒ 插话本身更早滑出
    //   timestamp < 窗口最旧时间戳
    // 判定成立即视为已确认，不再作为 pending 重放（调用方会从持久层清除）。
    val windowOldestId = history.mapNotNull { it.serverId.takeIf { id -> id > 0 } }.minOrNull()
    val windowOldestTs = history.mapNotNull { it.timestamp }.minOrNull()
    val pending = steering.filter { message ->
        val slidOutOfWindow =
            (windowOldestId != null && message.anchor > 0 && message.anchor < windowOldestId) ||
            (windowOldestTs != null && message.timestamp != null && message.timestamp < windowOldestTs)
        if (slidOutOfWindow && message.delivery != "发送失败") {
            false
        } else {
            val match = history.firstOrNull { it.serverId !in consumed && steeringAppearsInHistory(message, listOf(it)) }
            if (match == null) true else { consumed += match.serverId; acknowledged += message to match; false }
        }
    }
    return pending to acknowledged
}

fun mergeSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): List<HermesMessage> {
    val rows = history.toMutableList()
    pendingSteeringMessages(history, steering).forEach { message ->
        if (rows.any { it.localKey == message.key }) return@forEach
        val anchor = rows.indexOfLast { it.serverId == message.anchor && it.serverId > 0 }
        var index = if (anchor >= 0) anchor + 1 else rows.size
        while (index < rows.size && rows[index].localKey != null) index++
        rows.add(index, HermesMessage("user", message.text, attachments = message.attachments, localKey = message.key, delivery = message.delivery, timestamp = message.timestamp))
    }
    return rows
}
