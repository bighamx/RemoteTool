package com.chuckiehelper.mobile.nativeui

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
    // Some servers flatten command previews. Stop before an actual CLI command.
    return comments.split(Regex("\\s+(?=(?:ssh|pwsh|powershell|cmd|curl|wget|git|docker|dotnet|python|npm)\\s+(?:[\\\"'/-]|[A-Za-z0-9_@]))", RegexOption.IGNORE_CASE), limit = 2)
        .first().trim().trimEnd(':', '：').take(2000).takeIf { it.isNotBlank() }
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
