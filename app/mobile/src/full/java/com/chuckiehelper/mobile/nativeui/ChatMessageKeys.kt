package com.chuckiehelper.mobile.nativeui

/** Stable identity keeps image previews and scroll anchors with their message during insertions. */
internal data class ChatMessageItem(val key: String, val message: HermesMessage, val streaming: Boolean = false)

internal fun chatAssistantIdentity(run: String, item: String?, timestamp: Long?): String =
    item?.let { "server:${narrationMessageId(it)}" } ?: "stream:$run:$timestamp"

internal data class ChatTextStream(val run: String, val item: String?, val text: String,
    val timestamp: Long?, val completed: Boolean = false, val narration: Boolean = false) {
    val identity get() = chatAssistantIdentity(run, item, timestamp)
}

internal fun chatMessageItems(rows: List<HermesMessage>, stream: ChatTextStream? = null,
    assistantIdentities: Map<String, String> = emptyMap()): List<ChatMessageItem> {
    fun identity(row: HermesMessage): String =
        if (row.role == "assistant" && row.serverId > 0) "server:${row.serverId}"
        else if (row.role == "assistant" && assistantIdentities[row.localKey] != null) assistantIdentities.getValue(row.localKey!!)
        else row.localKey?.let { "local:$it" } ?: if (row.serverId > 0) "server:${row.serverId}" else
            "fallback:${row.role}:${row.timestamp}:${row.text.hashCode()}"

    // A stream is one list item before and after its native history row arrives.
    // Keep saved IDs untouched so unsaved text cannot enable message actions.
    val projected = rows.map { row ->
        if (stream != null && identity(row) == stream.identity && stream.text.startsWith(row.text))
            row.copy(text = stream.text, narration = row.narration || stream.narration) else row
    }.toMutableList()
    if (stream != null && stream.text.isNotBlank() && projected.none { identity(it) == stream.identity })
        projected += HermesMessage("assistant", stream.text, localKey = stream.identity,
            timestamp = stream.timestamp, narration = stream.narration)
    val seen = mutableMapOf<String, Int>()
    return projected.map { row ->
        val key = if (stream != null && row.localKey == stream.identity) stream.identity else identity(row)
        val occurrence = seen[key] ?: 0
        seen[key] = occurrence + 1
        ChatMessageItem("$key:$occurrence", row, stream != null && key == stream.identity && !stream.completed)
    }
}

internal fun chatMessageKeys(rows: List<HermesMessage>): List<String> = chatMessageItems(rows).map { it.key }
