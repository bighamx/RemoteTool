package com.chuckiehelper.mobile.nativeui

/** Stable partition: pins first; preserve the server's order within each group. */
internal fun <T> pinnedSessionsFirst(rows: List<T>, pinned: (T) -> Boolean): List<T> =
    rows.filter(pinned) + rows.filterNot(pinned)

internal fun confirmedSessionPin(result: org.json.JSONObject, expected: Boolean): Boolean {
    val value = (result.optJSONObject("session") ?: result).opt("pinned")
    return value is Boolean && value == expected
}

/** Keep list identity unique even when the agent returns repeated session records. */
internal fun <T> mergeSessionPage(existing: List<T>, incoming: List<T>, id: (T) -> String): List<T> {
    val rows = linkedMapOf<String, T>()
    existing.forEach { row ->
        val key = id(row)
        if (key.isNotBlank() && key != "null") rows.putIfAbsent(key, row)
    }
    val seen = mutableSetOf<String>()
    incoming.forEach { row ->
        val key = id(row)
        // The first result is the most recent record in the agent's sorted page.
        if (key.isNotBlank() && key != "null" && seen.add(key)) rows[key] = row
    }
    return rows.values.toList()
}
