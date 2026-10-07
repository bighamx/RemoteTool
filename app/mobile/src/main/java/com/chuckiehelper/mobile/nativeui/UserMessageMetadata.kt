package com.chuckiehelper.mobile.nativeui

/** Preserve a known creation time/identity when a lagging history snapshot omits it. */
internal fun retainUserMessageMetadata(history: List<HermesMessage>, previous: List<HermesMessage>): List<HermesMessage> {
    val known = previous.filter { it.role == "user" && it.serverId > 0 }.associateBy { it.serverId }
    return history.map { row ->
        val earlier = known[row.serverId]
        if (row.role == "user" && earlier != null && earlier.text == row.text)
            row.copy(timestamp = row.timestamp ?: earlier.timestamp, localKey = row.localKey ?: earlier.localKey)
        else row
    }
}
