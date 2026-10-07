package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject

data class AssistantNarration(val key: String, val session: String, val text: String, val anchor: Long,
    val userText: String, val timestamp: Long, val userTimestamp: Long? = null, val sequence: Long? = null,
    val run: String? = null, val messageId: String? = null, val streamed: Boolean = false, val userKey: String? = null)

/** Earlier caches inferred an anchor from the latest visible user and sometimes invented the time. */
fun restoreAssistantNarrations(rows: JSONArray): List<AssistantNarration> = rows.objects().mapNotNull { row ->
    if (row.optInt("positionVersion") != 1) return@mapNotNull null
    runCatching {
        AssistantNarration(row.getString("key"), row.getString("session"), row.getString("text"),
            row.optLong("anchor"), row.optString("userText"), row.getLong("timestamp"),
            row.optLong("userTimestamp").takeIf { it > 0 }, row.optLong("sequence", -1).takeIf { it >= 0 },
            row.optString("run").takeIf { it.isNotBlank() && it != "null" },
            row.optString("messageId").takeIf { it.isNotBlank() && it != "null" }, row.optBoolean("streamed"),
            row.optString("userKey").takeIf { it.isNotBlank() && it != "null" })
    }.getOrNull()?.takeIf { it.timestamp > 0 }
}

/** The message array is in server order; numeric Codex message IDs are hashes, not chronology. */
fun narrationAnchorIndex(history: List<HermesMessage>, note: AssistantNarration): Int {
    note.messageId?.let { id ->
        val message = history.indexOfFirst { it.role == "assistant" && it.serverId == narrationMessageId(id) }
        if (message >= 0) return (message - 1 downTo 0).firstOrNull { history[it].role == "user" } ?: -1
    }
    // The active run is explicitly bound to this submitted user message. Hermes may
    // stream before that user row is available in its history API; no latest-row guess.
    note.userKey?.let { key ->
        val explicit = history.indexOfFirst { it.role == "user" && (it.localKey == key ||
            note.anchor > 0 && it.serverId == note.anchor && it.text == note.userText) }
        if (explicit >= 0) {
            // A run can accept several interjections. Its original user binding
            // must not pull later commentary ahead of newer accepted instructions.
            val newer = (explicit + 1 until history.size).any {
                history[it].role == "user" && history[it].timestamp?.let { at -> at <= note.timestamp } == true
            }
            if (!newer) return explicit
        }
    }
    val users = history.indices.filter { history[it].role == "user" }
    val candidates = users.filter {
        val user = history[it]
        user.serverId > 0 && user.timestamp != null && user.timestamp <= note.timestamp
    }
    val latestTime = candidates.maxOfOrNull { history[it].timestamp!! }
    val sameTime = candidates.filter { history[it].timestamp == latestTime }
    val anchor = if (sameTime.size == 1) sameTime.single() else if (sameTime.isNotEmpty()) return -1 else {
        // A previously verified exact ID can survive a server temporarily omitting timestamps.
        // A repeated userText alone must never establish identity.
        if (note.anchor <= 0 || note.userTimestamp == null || note.userTimestamp > note.timestamp) return -1
        val verified = users.singleOrNull { history[it].serverId == note.anchor && history[it].text.trim() == note.userText.trim() }
            ?: return -1
        if (history[verified].timestamp != null) return -1 // Known timestamps override a stale hint.
        verified
    }
    val next = users.firstOrNull { it > anchor }
    if (next != null) {
        val nextTime = history[next].timestamp ?: return -1
        if (nextTime <= note.timestamp) return -1 // May still be an optimistic user row awaiting history.
    }
    return anchor
}

fun assistantNarrationEvent(key: String, session: String, text: String, timestamp: Long?,
    history: List<HermesMessage>, sequence: Long? = null, userKey: String? = null): AssistantNarration? {
    if (timestamp == null || timestamp <= 0 || text.isBlank()) return null
    val note = AssistantNarration(key, session, text, 0, "", timestamp, sequence = sequence, userKey = userKey)
    val anchor = narrationAnchorIndex(history, note)
    if (anchor < 0) return note // Re-evaluate after history arrives, never attach to the latest row.
    val user = history[anchor]
    return note.copy(anchor = user.serverId, userText = user.text, userTimestamp = user.timestamp)
}

internal fun acknowledgeNarrationUser(notes: List<AssistantNarration>, session: String, userKey: String,
    canonical: HermesMessage): List<AssistantNarration> = notes.map { note ->
    if (note.session == session && note.userKey == userKey && canonical.role == "user" && canonical.serverId > 0)
        note.copy(anchor = canonical.serverId, userText = canonical.text, userTimestamp = canonical.timestamp)
    else note
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
    if (row.optString("phase") == "commentary") return true
    val calls = row.opt("tool_calls")
    return when (calls) {
        is JSONArray -> calls.length() > 0
        is String -> runCatching { JSONArray(calls).length() > 0 }.getOrDefault(false)
        else -> false
    }
}

private val narrationWhitespace = Regex("\\s+")
private val narrationHeading = Regex("(?m)^\\s*#{1,6}\\s*")
private fun normalizedNarration(text: String) = narrationWhitespace.replace(narrationHeading.replace(text, "").trim(), " ")
internal fun narrationCovers(text: String, part: String): Boolean {
    val full = normalizedNarration(text); val short = normalizedNarration(part)
    return full == short || short.length >= 8 && full.contains(short) ||
        short.length >= 4 && short.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN } &&
        (full.startsWith(short) || full.endsWith(short))
}

internal fun narrationMessageId(id: String): Long = java.security.MessageDigest.getInstance("SHA-256")
    .digest(id.toByteArray(Charsets.UTF_8)).take(7).fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 255) } + 1

/** A history GET may lag the stream; never replace a growing native item with its older prefix. */
internal fun reconcilePendingAssistant(history: List<HermesMessage>, item: String?, text: String): List<HermesMessage> {
    if (item == null || text.isBlank()) return history
    val id = narrationMessageId(item)
    return history.map { row ->
        if (row.role == "assistant" && row.serverId == id && text.length > row.text.length && text.startsWith(row.text))
            row.copy(text = text)
        else row
    }
}

internal fun visiblePendingAssistant(history: List<HermesMessage>, item: String?, text: String): String =
    if (item != null && history.any { it.role == "assistant" && it.serverId == narrationMessageId(item) && narrationCovers(it.text, text) }) "" else text

internal fun upsertAssistantNarration(existing: List<AssistantNarration>, incoming: AssistantNarration,
    history: List<HermesMessage>): List<AssistantNarration> {
    val anchor = narrationAnchorIndex(history, incoming)
    val index = existing.indexOfFirst { note -> note.session == incoming.session && (
        note.key == incoming.key || incoming.messageId != null && note.messageId == incoming.messageId && note.run == incoming.run ||
            incoming.run != null && incoming.run == note.run && anchor >= 0 && narrationAnchorIndex(history, note) == anchor &&
            (narrationCovers(note.text, incoming.text) || narrationCovers(incoming.text, note.text))) }
    if (index < 0) return existing + incoming
    val previous = existing[index]
    // Real assistant text takes precedence over a comment extracted from a command.
    val chosen = when {
        incoming.streamed && !previous.streamed -> incoming
        previous.streamed && !incoming.streamed -> previous
        incoming.messageId != null && incoming.messageId == previous.messageId -> incoming
        incoming.text.length >= previous.text.length -> incoming
        else -> previous
    }
    val replacement = chosen.copy(key = previous.key, timestamp = minOf(previous.timestamp, incoming.timestamp),
        anchor = previous.anchor.takeIf { it > 0 } ?: incoming.anchor,
        userText = previous.userText.ifBlank { incoming.userText }, userTimestamp = previous.userTimestamp ?: incoming.userTimestamp)
    return existing.mapIndexed { i, note -> if (i == index) replacement else note }
}

/** Collapse cumulative native narration snapshots only, never final answers or user messages. */
private fun coalesceNativeNarrations(history: List<HermesMessage>): MutableList<HermesMessage> {
    val rows = mutableListOf<HermesMessage>()
    history.forEach { row ->
        val previous = rows.lastOrNull()
        if (row.role == "assistant" && row.narration && previous?.role == "assistant" && previous.narration &&
            row.attachments.isEmpty() && previous.attachments.isEmpty() &&
            (narrationCovers(previous.text, row.text) || narrationCovers(row.text, previous.text))) {
            if (row.text.length > previous.text.length) rows[rows.lastIndex] = previous.copy(text = row.text)
        } else rows += row
    }
    return rows
}

fun mergeAssistantNarrations(history: List<HermesMessage>, narrations: List<AssistantNarration>): List<HermesMessage> {
    // Rebuild local copies so a cached wrong position cannot survive via localKey deduplication.
    val rows = coalesceNativeNarrations(history.filterNot { it.serverId == 0L && it.localKey?.startsWith("narration-") == true })
    narrations.distinctBy { it.key }.sortedWith(compareBy<AssistantNarration> { it.timestamp }
        .thenBy { it.sequence ?: Long.MAX_VALUE }).forEach { note ->
        val anchor = narrationAnchorIndex(rows, note)
        if (anchor < 0) return@forEach
        val end = (anchor + 1 until rows.size).firstOrNull { rows[it].role == "user" } ?: rows.size
        val exact = note.messageId?.let { id -> (anchor + 1 until end).firstOrNull { rows[it].role == "assistant" && rows[it].serverId == narrationMessageId(id) } }
        val match = exact ?: (anchor + 1 until end).firstOrNull { index -> rows[index].role == "assistant" &&
            (narrationCovers(rows[index].text, note.text) || rows[index].serverId > 0 && rows[index].narration && narrationCovers(note.text, rows[index].text)) }
        if (match != null) {
            if (rows[match].narration || note.streamed && normalizedNarration(rows[match].text) == normalizedNarration(note.text))
                rows[match] = rows[match].copy(localKey = rows[match].localKey?.takeIf { it.startsWith("narration-") } ?: note.key, narration = true,
                    text = if (note.messageId != null && rows[match].serverId == narrationMessageId(note.messageId) &&
                        narrationCovers(note.text, rows[match].text) && note.text.length > rows[match].text.length) note.text else rows[match].text)
            return@forEach
        }
        val derived = (anchor + 1 until end).firstOrNull { rows[it].serverId == 0L && rows[it].localKey?.startsWith("narration-") == true &&
            narrationCovers(note.text, rows[it].text) }
        if (derived != null) { rows[derived] = rows[derived].copy(text = note.text); return@forEach }
        var insertion = anchor + 1
        while (insertion < end) {
            val row = rows[insertion]
            val time = row.timestamp ?: break
            if (time > note.timestamp) break
            insertion++
        }
        rows.add(insertion, HermesMessage("assistant", note.text, localKey = note.key, timestamp = note.timestamp, narration = true))
    }
    return coalesceNativeNarrations(rows)
}
