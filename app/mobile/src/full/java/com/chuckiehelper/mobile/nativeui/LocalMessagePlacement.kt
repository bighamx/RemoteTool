package com.chuckiehelper.mobile.nativeui

/** Keep server order intact, but place a local submission after everything known before its creation. */
internal fun insertLocalMessage(history: List<HermesMessage>, message: HermesMessage,
    existingIds: Set<Long>, anchor: Long = 0): List<HermesMessage> {
    if (history.any { message.localKey != null && it.localKey == message.localKey }) return history
    val known = history.indexOfLast { it.serverId > 0 && (it.serverId in existingIds || it.serverId == anchor) }
    var index = if (known >= 0) known + 1 else if (message.timestamp == null) history.size else 0
    while (index < history.size) {
        val row = history[index]
        val at = row.timestamp
        if (message.timestamp != null && at != null) {
            if (at > message.timestamp) break
        } else if (row.localKey == null) break // Missing times are not permission to reorder native rows.
        index++
    }
    return history.toMutableList().apply { add(index, message) }
}
