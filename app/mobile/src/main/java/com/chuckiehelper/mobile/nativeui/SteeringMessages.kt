package com.chuckiehelper.mobile.nativeui

data class SteeringMessage(
    val key: String,
    val session: String,
    val text: String,
    val existingIds: Set<Long>,
    val anchor: Long,
    val delivery: String = "正在发送",
    val timestamp: Long? = null,
)

fun steeringAppearsInHistory(message: SteeringMessage, history: List<HermesMessage>): Boolean =
    message.delivery != "发送失败" && history.any {
        it.role == "user" && it.serverId > 0 && it.serverId !in message.existingIds &&
            it.text.replace("\r\n", "\n").trim() == message.text.replace("\r\n", "\n").trim()
    }

fun pendingSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): List<SteeringMessage> {
    val consumed = mutableSetOf<Long>()
    return steering.filter { message ->
        val match = history.firstOrNull { it.serverId !in consumed && steeringAppearsInHistory(message, listOf(it)) }
        if (match == null) true else { consumed += match.serverId; false }
    }
}

fun mergeSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): List<HermesMessage> {
    val rows = history.toMutableList()
    pendingSteeringMessages(history, steering).forEach { message ->
        if (rows.any { it.localKey == message.key }) return@forEach
        val anchor = rows.indexOfLast { it.serverId == message.anchor && it.serverId > 0 }
        var index = if (anchor >= 0) anchor + 1 else rows.size
        while (index < rows.size && rows[index].localKey != null) index++
        rows.add(index, HermesMessage("user", message.text, localKey = message.key, delivery = message.delivery, timestamp = message.timestamp))
    }
    return rows
}
