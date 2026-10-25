package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject

private val narrationTerminalTools = setOf("terminal", "终端", "commandexecution", "powershell", "shell")
private val shellDirectoryPrefix = Regex("""^\s*cd\s+[^;&\r\n]+\s*(?:&&|;|&)\s*""", RegexOption.IGNORE_CASE)
private val echoedHeading = Regex("""^(?:echo|Write-Host|Write-Output)\s+(["'])([^\r\n]*?)\1(?=\s*(?:[;&\r\n]|$))""", RegexOption.IGNORE_CASE)

/** Extract explicit explanations, not executable commands or arbitrary console output. */
fun terminalNarration(tool: String, preview: String): String? {
    if (tool.lowercase().substringAfterLast('.') !in narrationTerminalTools) return null
    val source = preview.replace("\r\n", "\n").trimStart()
    val comments = source.lineSequence()
        .takeWhile { it.trimStart().startsWith("#") && !it.trimStart().startsWith("#!") }
        .map { it.trim().removePrefix("#").trim() }
        .filter { it.isNotBlank() && !it.matches(Regex("^(include|define|ifdef|ifndef|endif|pragma)\\b.*")) }
        .joinToString("\n")
    if (comments.isNotBlank()) return comments
    // Hermes sometimes writes its explanation as an explicit console section title.
    // Only a leading quoted heading is eligible, optionally preceded by `cd ... &&`.
    val command = shellDirectoryPrefix.replaceFirst(source, "")
    val title = echoedHeading.find(command)?.groupValues?.get(2)?.trim() ?: return null
    if (!title.startsWith("===") || title.none { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }) return null
    return title.trim('=').trim().takeIf { it.isNotBlank() }
}

/** Restore descriptions from the original arguments when SSE previews omit or shorten them. */
internal fun historyToolNarration(row: JSONObject): String? {
    if (row.optString("role") != "assistant") return null
    val raw = row.opt("tool_calls")
    val calls = when (raw) {
        is JSONArray -> raw
        is String -> if (raw.length <= 256 * 1024) runCatching { JSONArray(raw) }.getOrNull() else null
        else -> null
    } ?: return null
    return (0 until calls.length()).flatMap { index ->
        val function = calls.optJSONObject(index)?.optJSONObject("function") ?: return@flatMap emptyList()
        val args = when (val value = function.opt("arguments")) {
            is JSONObject -> value
            is String -> if (value.length <= 65536) runCatching { JSONObject(value) }.getOrNull() else null
            else -> null
        } ?: return@flatMap emptyList()
        val commands = when (val command = args.opt("command") ?: args.opt("commands")) {
            is String -> listOf(command)
            is JSONArray -> (0 until command.length()).mapNotNull {
                when (val item = command.opt(it)) {
                    is String -> item
                    is JSONObject -> item.optString("command").takeIf(String::isNotBlank)
                    else -> null
                }
            }
            else -> emptyList()
        }
        commands.mapNotNull { terminalNarration(function.optString("name"), it) }
    }.distinct().joinToString("\n").takeIf { it.isNotBlank() }
}
