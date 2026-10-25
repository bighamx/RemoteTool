package com.chuckiehelper.mobile.nativeui

import java.net.URI
import java.net.URLDecoder
import org.json.JSONObject

/** Only explicit absolute file destinations; web URLs and network shares remain outside attachments. */
internal fun markdownFilePath(destination: String): String? = runCatching {
    var value = destination.trim().substringBefore('#').substringBefore('?')
    if (value.startsWith("file:", true)) {
        val uri = URI(value)
        if (!uri.scheme.equals("file", true) || (!uri.host.isNullOrEmpty() && !uri.host.equals("localhost", true))) return null
        value = uri.rawPath ?: return null
    } else if (value.contains("://") || value.startsWith("//") || value.startsWith("\\\\")) return null
    // '+' is a legal path character, never a URL form-space.
    value = URLDecoder.decode(value.replace(Regex("%(?![0-9A-Fa-f]{2})"), "%25").replace("+", "%2B"), "UTF-8").replace('\\', '/')
    if (Regex("^/[A-Za-z]:/").containsMatchIn(value)) value = value.drop(1)
    value = value.replace(Regex(":\\d+(?::\\d+)?$"), "")
    if (!(Regex("^[A-Za-z]:/").containsMatchIn(value) || value.startsWith('/')) || value.startsWith("//")) return null
    val prefix = if (Regex("^[A-Za-z]:/").containsMatchIn(value)) value.take(3) else "/"
    val parts = mutableListOf<String>()
    for (part in value.drop(prefix.length).split('/')) when (part) {
        "", "." -> Unit
        ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else Unit
        else -> parts += part
    }
    prefix + parts.joinToString("/") + if (value.endsWith('/') && parts.isNotEmpty()) "/" else ""
}.getOrNull()

internal fun sameAttachmentPath(first: String, second: String): Boolean {
    val a = first.replace('\\', '/')
    val b = second.replace('\\', '/')
    return a.equals(b, ignoreCase = Regex("^[A-Za-z]:/").containsMatchIn(a))
}

internal fun markdownLinkAttachment(path: String): JSONObject? {
    if (path.endsWith('/')) return null
    val name = path.substringAfterLast('/')
    if (name.isBlank()) return null
    val identity = if (Regex("^[A-Za-z]:/").containsMatchIn(path)) path.lowercase(java.util.Locale.ROOT) else path
    val id = java.security.MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    val ext = name.substringAfterLast('.', "").lowercase()
    val mime = if (ext in setOf("md", "markdown")) "text/markdown"
        else android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    return obj("id" to "link-$id", "name" to name, "path" to path, "mediaPath" to path,
        "mime" to mime, "url" to "/api/files/stream?path=${q(path)}", "localLink" to true)
}

/** Old device services can resolve links through their existing authenticated file API. */
internal suspend fun resolveMarkdownAttachment(api: NativeApi, file: JSONObject): JSONObject {
    if (!file.optBoolean("localLink")) return file
    val path = file.getString("path")
    var parent = path.substringBeforeLast('/', "/").ifBlank { "/" }
    if (Regex("^[A-Za-z]:$").matches(parent)) parent += "/"
    val entry = api.json("/api/files/list?path=${q(parent)}").array("data").objects().firstOrNull {
        !it.optBoolean("isDirectory") && sameAttachmentPath(it.optString("path"), path)
    } ?: throw java.io.IOException("文件不存在或不可访问")
    return JSONObject(file.toString()).put("size", entry.getLong("size"))
}

/** Use the same CommonMark syntax as the existing renderer, including reference links and code exclusion. */
private val fileLinkParser = org.commonmark.parser.Parser.builder().build()
internal fun markdownFilePaths(text: String): List<String> {
    if ('[' !in text) return emptyList()
    val paths = mutableListOf<String>()
    fun add(destination: String) {
        if (paths.size >= 32) return
        markdownFilePath(destination)?.let { path ->
            if (paths.none { sameAttachmentPath(it, path) }) paths += path
        }
    }
    fileLinkParser.parse(text).accept(object : org.commonmark.node.AbstractVisitor() {
        override fun visit(link: org.commonmark.node.Link) { add(link.destination); visitChildren(link) }
        override fun visit(image: org.commonmark.node.Image) { add(image.destination); visitChildren(image) }
    })
    return paths
}
