package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject

data class AssistantNarration(val key: String, val session: String, val text: String, val anchor: Long,
    val userText: String, val timestamp: Long)

/** Extract leading explanatory comments, never shell commands or tool results. */
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
    val rows = history.toMutableList()
    narrations.forEach { note ->
        if (rows.any { it.localKey == note.key }) return@forEach
        var anchor = rows.indexOfLast { it.role == "user" && note.anchor > 0 && it.serverId == note.anchor }
        if (anchor < 0) anchor = rows.indexOfLast { it.role == "user" && it.text.trim() == note.userText.trim() }
        if (anchor < 0) return@forEach
        val end = (anchor + 1 until rows.size).firstOrNull { rows[it].role == "user" } ?: rows.size
        if (rows.subList(anchor + 1, end).any { it.role == "assistant" &&
                (it.text.trim() == note.text.trim() || it.text.split("\n\n").any { part -> part.trim() == note.text.trim() }) }) return@forEach
        var insertion = anchor + 1
        while (insertion < end && rows[insertion].localKey?.startsWith("narration-") == true) insertion++
        rows.add(insertion, HermesMessage("assistant", note.text, localKey = note.key, timestamp = note.timestamp))
    }
    return rows
}
