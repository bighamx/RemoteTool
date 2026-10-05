package com.chuckiehelper.mobile.nativeui

/** Stable identity keeps image previews and scroll anchors with their message during insertions. */
internal fun chatMessageKeys(rows: List<HermesMessage>): List<String> {
    val seen = mutableMapOf<String, Int>()
    return rows.map { row ->
        val identity = row.localKey?.let { "local:$it" } ?: if (row.serverId > 0) "server:${row.serverId}" else
            "fallback:${row.role}:${row.timestamp}:${row.text.hashCode()}"
        val occurrence = seen[identity] ?: 0
        seen[identity] = occurrence + 1
        "$identity:$occurrence"
    }
}
