package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

data class HermesPresentation(val text: String, val files: List<JSONObject>, val unavailable: List<String>)
data class HermesMediaText(val text: String, val paths: List<String>)

fun extractHermesMedia(text: String): HermesMediaText = transformHermesMedia(text) { true }

/** Delivery markers are whole lines outside Markdown code, with an absolute file path. */
private fun transformHermesMedia(text: String, remove: (String) -> Boolean): HermesMediaText {
    val paths = mutableListOf<String>()
    var fenceChar: Char? = null
    var fenceLength = 0
    val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    val marker = Regex("^ {0,3}MEDIA:[ \\t]*(.+?)[ \\t]*$", RegexOption.IGNORE_CASE)
    val cleaned = text.replace("\r\n", "\n").split('\n').map { line ->
        val delimiter = fence.matchEntire(line)
        if (fenceChar != null) {
            if (delimiter != null && delimiter.groupValues[1][0] == fenceChar &&
                delimiter.groupValues[1].length >= fenceLength && delimiter.groupValues[2].isBlank()) fenceChar = null
            line
        } else if (delimiter != null) {
            fenceChar = delimiter.groupValues[1][0]; fenceLength = delimiter.groupValues[1].length
            line
        } else {
            val path = marker.matchEntire(line)?.groupValues?.get(1)?.trim()?.trim('"', '\'')?.replace('\\', '/')
            val absolute = path != null && (Regex("^[a-zA-Z]:/").containsMatchIn(path) || path.startsWith('/'))
            if (absolute) {
                paths += path!!
                if (remove(path)) "" else line
            } else line
        }
    }.joinToString("\n")
    return HermesMediaText(cleaned, paths)
}

fun presentHermesMessage(text: String, attached: List<JSONObject>, available: List<JSONObject>, role: String = "assistant"): HermesPresentation {
    val display = codexQuestionReplyDisplay(text)
    if (role != "assistant") return HermesPresentation(display, attached, emptyList())
    val files = attached.toMutableList()
    val unavailable = mutableListOf<String>()
    val parsed = transformHermesMedia(display) { path ->
        val name = path.substringAfterLast('/')
        val candidates = (attached + available).distinctBy { it.optString("id") }.filter {
            it.optString("name") == name
        }
        val expectedOutbox = Regex("/outbox/([^/]+)/").find(path)?.groupValues?.get(1)
        val file = candidates.firstOrNull {
            it.optString("mediaPath").replace('\\', '/').equals(path, ignoreCase = true)
        } ?: candidates.firstOrNull {
            val key = it.optString("messageKey")
            key.isNotBlank() && path.contains("/outbox/$key/")
        } ?: candidates.singleOrNull().takeIf { expectedOutbox == null }
        if (file != null) {
            if (files.none { it.optString("id") == file.optString("id") }) files += file
            true
        } else {
            unavailable += name
            false // Keep unresolved text visible instead of silently removing it.
        }
    }
    return HermesPresentation(parsed.text, files, unavailable.distinct())
}

fun needsMediaCatalogRefresh(text: String, available: List<JSONObject>): Boolean =
    presentHermesMessage(text, emptyList(), available).unavailable.isNotEmpty()

fun hermesFileKind(file: JSONObject): String {
    val mime = file.optString("mime")
    val ext = file.optString("name").substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") || ext in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp") -> "图片"
        mime.startsWith("video/") || ext in listOf("mp4", "mkv", "webm", "mov", "avi", "wmv", "m4v") -> "视频"
        mime.startsWith("audio/") || ext in listOf("mp3", "m4a", "aac", "wav", "ogg", "flac", "opus") -> "音频"
        mime.startsWith("text/") || ext in listOf("txt", "md", "json", "log", "csv", "yaml", "yml", "xml", "ini") -> "文本"
        else -> "文档"
    }
}
