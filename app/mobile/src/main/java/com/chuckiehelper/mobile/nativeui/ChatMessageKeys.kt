package com.chuckiehelper.mobile.nativeui

/** Stable identity keeps image previews and scroll anchors with their message during insertions. */
internal data class ChatMessageItem(val key: String, val message: HermesMessage)

internal fun chatMessageItems(rows: List<HermesMessage>): List<ChatMessageItem> {
    val seen = mutableMapOf<String, Int>()
    return rows.map { row ->
        val identity = row.localKey?.let { "local:$it" } ?: if (row.serverId > 0) "server:${row.serverId}" else
            "fallback:${row.role}:${row.timestamp}:${row.text.hashCode()}"
        val occurrence = seen[identity] ?: 0
        seen[identity] = occurrence + 1
        ChatMessageItem("$identity:$occurrence", row)
    }
}

internal fun chatMessageKeys(rows: List<HermesMessage>): List<String> = chatMessageItems(rows).map { it.key }
