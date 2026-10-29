package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject

/** Native multimodal blocks are a transport shape, never a printable JSON body. */
internal fun agentMessageContentText(value: Any?, hasAttachments: Boolean = false): String {
    if (value == null || value == JSONObject.NULL) return ""
    val raw = value.toString()
    val nativeArray = value is JSONArray
    val candidate = raw.trimStart().removePrefix("\u0000json:")
    if (!nativeArray && !candidate.startsWith('[')) return raw
    val parts = value as? JSONArray ?: runCatching { JSONArray(candidate) }.getOrNull() ?: return raw
    if (parts.length() == 0) return if (nativeArray) "" else raw
    val blocks = (0 until parts.length()).map { parts.optJSONObject(it) ?: return raw }
    if (blocks.any { it.optString("type") !in setOf("text", "input_text", "image_url", "input_image") }) return raw
    val imageBlocks = blocks.filter { it.optString("type") in setOf("image_url", "input_image") }
    // Old App snapshots serialized the entire array into text. Recognize only
    // actual media envelopes, so ordinary user JSON/examples remain untouched.
    if (!nativeArray && !hasAttachments && imageBlocks.none {
        val image = it.opt("image_url")
        val url = if (image is JSONObject) image.optString("url") else image as? String
            ?: it.optString("url").ifBlank { it.optString("image_url") }
        url.startsWith("data:image/")
    }) return raw
    return blocks.filter { it.optString("type") in setOf("text", "input_text") }
        .mapNotNull { it.opt("text") as? String }.joinToString("\n")
}
