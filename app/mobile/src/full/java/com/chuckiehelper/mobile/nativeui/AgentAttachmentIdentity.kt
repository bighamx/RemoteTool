package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Upload IDs and Hermes database IDs name different representations of the same file. */
internal fun sameAgentAttachment(left: JSONObject, right: JSONObject): Boolean {
    val leftIds = listOf(left.optString("id"), left.optString("uploadId")).filter { it.isNotBlank() }
    val rightIds = listOf(right.optString("id"), right.optString("uploadId")).filter { it.isNotBlank() }
    if (leftIds.any { it in rightIds }) return true
    val hash = left.optString("sha256")
    return Regex("[0-9a-fA-F]{64}").matches(hash) && hash.equals(right.optString("sha256"), ignoreCase = true)
}

internal fun containsAgentAttachments(actual: List<JSONObject>, expected: List<JSONObject>): Boolean {
    val remaining = actual.toMutableList()
    // Hermes deduplicates identical inline image bytes; repeated uploads need one native card.
    val unique = mergeAgentAttachments(emptyList(), expected)
    return unique.all { file ->
        val index = remaining.indexOfFirst { sameAgentAttachment(it, file) }
        if (index < 0) false else { remaining.removeAt(index); true }
    }
}

internal fun mergeAgentAttachments(canonical: List<JSONObject>, local: List<JSONObject>): List<JSONObject> =
    canonical.toMutableList().apply {
        local.forEach { file -> if (none { sameAgentAttachment(it, file) }) add(file) }
    }
