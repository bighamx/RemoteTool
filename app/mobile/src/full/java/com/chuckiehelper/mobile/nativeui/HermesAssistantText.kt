package com.chuckiehelper.mobile.nativeui

/** Strip only a provider's leading reasoning envelope, never quoted tags or Markdown code. */
internal fun hermesAssistantText(raw: String): String {
    var text = raw
    while (true) {
        val head = text.trimStart()
        if (head.isNotEmpty() && listOf("<think>", "</think>").any { it.startsWith(head, ignoreCase = true) }) return ""
        if (head.startsWith("</think>", ignoreCase = true)) {
            text = head.substring(8).trimStart()
        } else if (head.startsWith("<think>", ignoreCase = true)) {
            val end = head.indexOf("</think>", startIndex = 7, ignoreCase = true)
            if (end < 0) return ""
            text = head.substring(end + 8).trimStart()
        } else return text
    }
}

/** Legacy Hermes SSE has no item ID. Match exact text within its native user window.
 * The native append order remains authoritative even when an injected user was stamped a few
 * milliseconds after its reply. Never match identical text from an older turn by text alone. */
internal fun legacyAssistantIndex(history: List<HermesMessage>, text: String, timestamp: Long?, userId: Long? = null,
    acknowledged: Set<Long> = emptySet()): Int? {
    if (timestamp == null || text.isBlank()) return null
    val clean = hermesAssistantText(text).trim()
    if (clean.isEmpty()) return null
    val bound = history.indexOfFirst { it.role == "user" && userId != null && userId > 0 && it.serverId == userId }
    val users = history.indices.filter { history[it].role == "user" && history[it].serverId > 0 &&
        history[it].timestamp?.let { time -> time <= timestamp + 50 } == true }
    val latestTime = users.maxOfOrNull { history[it].timestamp!! }
    val observed = users.filter { history[it].timestamp == latestTime }.singleOrNull() ?: -1
    val user = maxOf(bound, observed)
    val end = if (user >= 0) (user + 1 until history.size).firstOrNull { history[it].role == "user" } ?: history.size else -1
    val candidates = history.indices.filter { index ->
        val row = history[index]
        row.role == "assistant" && row.serverId > 0 && row.serverId !in acknowledged && row.timestamp != null &&
            (if (user >= 0) index in user + 1 until end else kotlin.math.abs(row.timestamp - timestamp) <= 1_000) &&
            hermesAssistantText(row.text).trim() == clean
    }
    return if (user >= 0) candidates.firstOrNull() else candidates.singleOrNull()
}

internal fun acknowledgedHermesNarrations(history: List<HermesMessage>, notes: List<AssistantNarration>): Map<String, Long> {
    val matched = linkedMapOf<String, Long>()
    notes.filter { it.streamed && it.messageId == null && it.messageUid == null }.sortedWith(compareBy<AssistantNarration> { it.timestamp }.thenBy { it.sequence }).forEach { note ->
        legacyAssistantIndex(history, note.text, note.timestamp, note.anchor, matched.values.toSet())?.let { matched[note.key] = history[it].serverId }
    }
    return matched
}
