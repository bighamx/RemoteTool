package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

data class HermesPresentation(val text: String, val files: List<JSONObject>, val unavailable: List<String>)
data class HermesMediaText(val text: String, val paths: List<String>)

fun extractHermesMedia(text: String): HermesMediaText {
    val paths = mutableListOf<String>()
    val cleaned = Regex("(?m)^[ \\t]*MEDIA:[ \\t]*(.+?)[ \\t]*$", RegexOption.IGNORE_CASE).replace(text) { match ->
        paths += match.groupValues[1].trim().trim('"', '\'', '`').replace('\\', '/')
        ""
    }.replace(Regex("\\n{3,}"), "\n\n").trim()
    return HermesMediaText(cleaned, paths)
}

fun presentHermesMessage(text: String, attached: List<JSONObject>, available: List<JSONObject>): HermesPresentation {
    val files = attached.toMutableList()
    val unavailable = mutableListOf<String>()
    val parsed = extractHermesMedia(text)
    parsed.paths.forEach { path ->
        val name = path.substringAfterLast('/')
        val candidates = (attached + available).distinctBy { it.optString("id") }.filter {
            it.optString("name") == name
        }
        val file = candidates.firstOrNull {
            val key = it.optString("messageKey")
            key.isNotBlank() && path.contains("/outbox/$key/")
        } ?: candidates.singleOrNull()
        if (file != null) {
            if (files.none { it.optString("id") == file.optString("id") }) files += file
        } else unavailable += name
    }
    return HermesPresentation(parsed.text, files, unavailable.distinct())
}

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
