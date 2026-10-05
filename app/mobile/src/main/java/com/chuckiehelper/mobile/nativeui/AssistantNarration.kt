package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject

data class AssistantNarration(val key: String, val session: String, val text: String, val anchor: Long,
    val userText: String, val timestamp: Long, val userTimestamp: Long? = null, val sequence: Long? = null)

/** Earlier caches inferred an anchor from the latest visible user and sometimes invented the time. */
fun restoreAssistantNarrations(rows: JSONArray): List<AssistantNarration> = rows.objects().mapNotNull { row ->
    if (row.optInt("positionVersion") != 1) return@mapNotNull null
    runCatching {
        AssistantNarration(row.getString("key"), row.getString("session"), row.getString("text"),
            row.optLong("anchor"), row.optString("userText"), row.getLong("timestamp"),
            row.optLong("userTimestamp").takeIf { it > 0 }, row.optLong("sequence", -1).takeIf { it >= 0 })
    }.getOrNull()?.takeIf { it.timestamp > 0 }
}

/** The message array is in server order; numeric Codex message IDs are hashes, not chronology. */
fun narrationAnchorIndex(history: List<HermesMessage>, note: AssistantNarration): Int {
    val users = history.indices.filter { history[it].role == "user" }
    val candidates = users.filter {
        val user = history[it]
        user.serverId > 0 && user.timestamp != null && user.timestamp <= note.timestamp
    }
    val latestTime = candidates.maxOfOrNull { history[it].timestamp!! }
    val sameTime = candidates.filter { history[it].timestamp == latestTime }
    val anchor = if (sameTime.size == 1) sameTime.single() else if (sameTime.isNotEmpty()) return -1 else {
        // A previously verified exact ID can survive a server temporarily omitting timestamps.
        // A repeated userText alone must never establish identity.
        if (note.anchor <= 0 || note.userTimestamp == null || note.userTimestamp > note.timestamp) return -1
        val verified = users.singleOrNull { history[it].serverId == note.anchor && history[it].text.trim() == note.userText.trim() }
            ?: return -1
        if (history[verified].timestamp != null) return -1 // Known timestamps override a stale hint.
        verified
    }
    val next = users.firstOrNull { it > anchor }
    if (next != null) {
        val nextTime = history[next].timestamp ?: return -1
        if (nextTime <= note.timestamp) return -1 // May still be an optimistic user row awaiting history.
    }
    return anchor
}

fun assistantNarrationEvent(key: String, session: String, text: String, timestamp: Long?,
    history: List<HermesMessage>, sequence: Long? = null): AssistantNarration? {
    if (timestamp == null || timestamp <= 0 || text.isBlank()) return null
    val note = AssistantNarration(key, session, text, 0, "", timestamp, sequence = sequence)
    val anchor = narrationAnchorIndex(history, note)
    if (anchor < 0) return note // Re-evaluate after history arrives, never attach to the latest row.
    val user = history[anchor]
    return note.copy(anchor = user.serverId, userText = user.text, userTimestamp = user.timestamp)
}

/** Extract leading comments; a flattened preview may include command text in the same line. */
fun terminalNarration(tool: String, preview: String): String? {
    if (tool.lowercase() !in setOf("terminal", "终端", "commandexecution", "powershell", "shell")) return null
    val comments = preview.replace("\r\n", "\n").trimStart().lineSequence()
        .takeWhile { it.trimStart().startsWith("#") && !it.trimStart().startsWith("#!") }
        .map { it.trim().removePrefix("#").trim() }
        .filter { it.isNotBlank() && !it.matches(Regex("^(include|define|ifdef|ifndef|endif|pragma)\\b.*")) }
        .joinToString("\n")
    if (comments.isBlank()) return null
    // Keep the original explanation for history reconciliation; display trimming is separate.
    return comments
}

/** Count Unicode code points, so neither emoji nor supplementary Han characters are split. */
fun truncateNarration(text: String): String {
    val result = StringBuilder(text.length.coerceAtMost(2048))
    var index = 0
    var nonChinese = 0
    while (index < text.length) {
        val point = text.codePointAt(index)
        if (Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN) {
            nonChinese = 0
            result.appendCodePoint(point)
        } else {
            nonChinese++
            if (nonChinese <= 30) result.appendCodePoint(point)
            else if (nonChinese == 31) result.append('…')
        }
        index += Character.charCount(point)
    }
    return result.toString()
}

/** Alter only known narration spans; final answers, attachment parsing and raw history stay intact. */
fun displayNarration(text: String, narration: Boolean, spans: List<String> = emptyList()): String {
    if (narration) return truncateNarration(text)
    var display = text
    for (span in spans.filter { it.isNotBlank() }.distinct().sortedByDescending { it.length })
        display = display.replace(span, truncateNarration(span))
    return display
}

/** Hermes persists interim assistant text together with its tool calls. No code-content guessing. */
fun isAssistantNarration(row: JSONObject): Boolean {
    if (row.optString("role") != "assistant") return false
    val calls = row.opt("tool_calls")
    return when (calls) {
        is JSONArray -> calls.length() > 0
        is String -> runCatching { JSONArray(calls).length() > 0 }.getOrDefault(false)
        else -> false
    }
}

fun mergeAssistantNarrations(history: List<HermesMessage>, narrations: List<AssistantNarration>): List<HermesMessage> {
    // Rebuild local copies so a cached wrong position cannot survive via localKey deduplication.
    val rows = history.filterNot { it.localKey?.startsWith("narration-") == true }.toMutableList()
    narrations.distinctBy { it.key }.sortedWith(compareBy<AssistantNarration> { it.timestamp }
        .thenBy { it.sequence ?: Long.MAX_VALUE }).forEach { note ->
        val anchor = narrationAnchorIndex(rows, note)
        if (anchor < 0) return@forEach
        val end = (anchor + 1 until rows.size).firstOrNull { rows[it].role == "user" } ?: rows.size
        if (rows.subList(anchor + 1, end).any { it.role == "assistant" &&
                (it.text.trim() == note.text.trim() || it.text.split("\n\n").any { part -> part.trim() == note.text.trim() }) }) return@forEach
        var insertion = anchor + 1
        while (insertion < end) {
            val row = rows[insertion]
            val time = row.timestamp ?: break
            if (time > note.timestamp) break
            insertion++
        }
        rows.add(insertion, HermesMessage("assistant", note.text, localKey = note.key, timestamp = note.timestamp))
    }
    return rows
}
