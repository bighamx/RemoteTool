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
    steeringAppearsInHistory(message, history, emptySet())

/** consumedLines: (serverId, 文本行) 粒度——网关会把 turn 活跃期间的多条消息合并进一行
    （OOB 外壳，一行一条），合并行的每个文本行可以各确认一条 steering。 */
fun steeringAppearsInHistory(message: SteeringMessage, history: List<HermesMessage>, consumedLines: Set<Pair<Long, String>>): Boolean =
    message.delivery != "发送失败" && message.delivery != BUSY_STEERING_DELIVERY && history.any { row ->
        row.role == "user" && row.serverId > 0 && row.serverId !in message.existingIds &&
            (row.requestKey == message.key || message.delivery == "已送达" && row.requestKey == null && message.timestamp != null &&
                // 只保留下界：插话在网关队列里可能排队很久才被合并入库（实测 11 分钟），
                // 上界会把这种合法延迟挡掉。新行保护由 existingIds 边界承担。
                row.timestamp?.let { at -> at >= message.timestamp - 10_000L } == true) &&
            (message.attachments.isEmpty() || row.attachments.map { file -> file.optString("id") }
                .containsAll(message.attachments.map { file -> file.optString("id") })) &&
            run {
                val text = message.text.replace("\r\n", "\n").trim()
                val rowText = agentUserMessageText("hermes", row.text).replace("\r\n", "\n").trim()
                if (rowText == text) true
                else rowText.contains('\n') && rowText.split('\n').any { line ->
                    line.trim() == text && (row.serverId to line.trim()) !in consumedLines
                }
            }
    }

fun pendingSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): List<SteeringMessage> {
    return reconcileSteeringMessages(history, steering).first
}

/** 发送失败的记录超过 10 分钟即视为放弃：不再重放（失败重试应由用户主动操作，而不是每次进会话重发旧文）。 */
fun isAbandonedSteering(message: SteeringMessage, now: Long = System.currentTimeMillis()): Boolean {
    if (message.timestamp == null) return false
    val age = now - message.timestamp
    // 发送失败 10 分钟、发送状态待核对 30 分钟即放弃：不再重放/挂着（失败重试应由用户主动
    // 操作，而不是每次进会话重发旧文）。hermes 渠道没有 request-key，「待核对」永远等不到
    // 确认；放弃后若消息实际已入库，原生历史行仍会正常显示。
    return (message.delivery in setOf("发送失败", BUSY_STEERING_DELIVERY) && age > 10 * 60 * 1000L) ||
        (message.delivery == "发送状态待核对" && age > 30 * 60 * 1000L)
}

fun reconcileSteeringMessages(history: List<HermesMessage>, steering: List<SteeringMessage>): Pair<List<SteeringMessage>, List<Pair<SteeringMessage, HermesMessage>>> {
    val consumed = mutableSetOf<Long>()
    val consumedLines = mutableSetOf<Pair<Long, String>>()
    val acknowledged = mutableListOf<Pair<SteeringMessage, HermesMessage>>()
    val pending = steering.filter { message ->
        // 不设上界后同文本行可能有多条，取发送时刻之后最早的一行（确定性匹配），
        // consumed/consumedLines 保证一行（或一行内的一个文本行）只确认一条 steering。
        val match = history.filter { it.serverId !in consumed && steeringAppearsInHistory(message, listOf(it), consumedLines) }
            .minByOrNull { it.timestamp ?: Long.MAX_VALUE }
        if (match != null) {
            // 合并行（剥壳后多行）按文本行粒度消费，让同一行确认多条 steering；
            // 单行消息维持整行消费，防止一条入库行确认两条同文本重发。
            val rowText = agentUserMessageText("hermes", match.text).replace("\r\n", "\n").trim()
            val text = message.text.replace("\r\n", "\n").trim()
            if (rowText != text && rowText.contains('\n')) consumedLines += match.serverId to text
            else consumed += match.serverId
            acknowledged += message to match; false
        } else if (message.attachments.isEmpty() && outsideSteeringWindow(history, message)) {
            false
        } else true
    }
    return pending to acknowledged
}

private fun outsideSteeringWindow(history: List<HermesMessage>, message: SteeringMessage): Boolean {
    // 96 = 近满页（limit=100）：窗口从最新端加载，steer 比窗口最旧行还旧且不在窗内，
    // 说明它从未入库。原阈值 500 永远达不到（实际窗口就是 100），该分支形同虚设。
    if (history.size < 96 || message.delivery != "已送达" || message.timestamp == null) return false
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
