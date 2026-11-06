package com.chuckiehelper.mobile.nativeui

/** Insert only live narration; keep every native row in its original relative order. */
internal fun projectHermesReasoning(history: List<HermesMessage>, reasoning: List<HermesMessage>): List<HermesMessage> {
    if (reasoning.isEmpty()) return history
    val rows = history.toMutableList()
    reasoning.forEach { note ->
        val time = note.timestamp
        val anchor = if (time == null) -1 else rows.indexOfLast {
            it.role == "user" && it.timestamp?.let { at -> at <= time + 50 } == true
        }
        val next = (anchor + 1 until rows.size).firstOrNull { index ->
            rows[index].role == "user" && (anchor >= 0 || time != null && rows[index].timestamp?.let { it > time } == true)
        } ?: rows.size
        val before = (anchor + 1 until next).firstOrNull { index ->
            rows[index].role == "assistant" && time != null && rows[index].timestamp?.let { it > time } == true
        } ?: next
        rows.add(before, note)
    }
    return rows
}
