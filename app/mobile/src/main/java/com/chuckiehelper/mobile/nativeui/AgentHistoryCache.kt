package com.chuckiehelper.mobile.nativeui

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray

/** Private offline snapshots. A successful read replaces the entire requested window. */
internal class AgentHistoryCache(private val root: File, private val scope: String) {
    private fun key(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private val directory get() = File(root, key(scope))
    private fun file(session: String) = File(directory, key(session) + ".json")

    @Synchronized fun read(session: String): List<HermesMessage>? = runCatching {
        val path = file(session)
        if (!path.isFile || path.length() > 8 * 1024 * 1024) return null
        decodeHistorySnapshot(JSONArray(path.readText(Charsets.UTF_8)))
    }.getOrNull()

    @Synchronized fun write(session: String, messages: List<HermesMessage>) {
        runCatching {
            val content = encodeHistorySnapshot(messages).toString().toByteArray(Charsets.UTF_8)
            if (content.size > 8 * 1024 * 1024) { remove(session); return }
            val path = file(session)
            path.parentFile!!.mkdirs()
            val temporary = File(path.parentFile, path.name + ".tmp")
            temporary.writeBytes(content)
            java.nio.file.Files.move(temporary.toPath(), path.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            directory.listFiles { f -> f.extension == "json" }?.sortedByDescending { it.lastModified() }
                ?.drop(50)?.forEach { it.delete() }
        }
    }

    @Synchronized fun remove(session: String) { file(session).delete() }
}

internal fun encodeHistorySnapshot(messages: List<HermesMessage>) = JSONArray(messages.map { message ->
    obj("role" to message.role, "text" to message.text, "id" to message.serverId,
        "attachments" to JSONArray(message.attachments), "timestamp" to message.timestamp,
        "narration" to message.narration, "turnId" to message.nativeTurnId, "editable" to message.editable)
})

internal fun decodeHistorySnapshot(rows: JSONArray): List<HermesMessage> = rows.objects().map { row ->
    HermesMessage(row.getString("role"), row.getString("text"), row.getLong("id"),
        row.array("attachments").objects(), timestamp = parseMessageTimestamp(row.opt("timestamp")),
        narration = row.optBoolean("narration"), nativeTurnId = row.optString("turnId").takeUnless { it.isBlank() || it == "null" },
        editable = row.optBoolean("editable", true))
}

/** Pending submissions are temporary trailing rows, never position hints for native history. */
internal fun appendPendingHistory(history: List<HermesMessage>, pending: List<HermesMessage>, currentKeys: Set<String> = emptySet()): List<HermesMessage> {
    val latestNativeTime = history.filter { it.serverId > 0 }.mapNotNull { it.timestamp }.maxOrNull()
    return history + pending.distinctBy { it.localKey }.filter { row ->
        // A restored pending record is not proof of a new message. Keep its durable
        // receipt separately, but never append older/undated submissions after newer history.
        val belongsAtEnd = row.timestamp?.let { time ->
            if (latestNativeTime != null) time >= latestNativeTime else row.localKey in currentKeys
        } == true
        belongsAtEnd && row.serverId == 0L && row.localKey != null && history.none { it.localKey == row.localKey }
    }
}

/** A current send remains visible until its native row arrives, even if an assistant row arrives first. */
internal fun projectCurrentSubmission(history: List<HermesMessage>, message: HermesMessage, existingIds: Set<Long>): List<HermesMessage> {
    if (history.any { it.localKey == message.localKey }) return history
    val boundary = history.indexOfFirst { it.serverId > 0 && it.serverId !in existingIds }
        .takeIf { it >= 0 } ?: history.size
    return history.toMutableList().apply { add(boundary, message) }
}

internal fun attachConfirmedSubmission(history: List<HermesMessage>, id: Long, attachments: List<org.json.JSONObject>): List<HermesMessage> =
    history.map { row -> if (row.serverId == id && row.role == "user") row.copy(
        attachments = (row.attachments + attachments).distinctBy { it.optString("id") }, delivery = null
    ) else row }

/** Only the live task contributes an overlay. Native rows are never moved or coalesced. */
internal fun withActiveNarrations(history: List<HermesMessage>, narrations: List<AssistantNarration>, activeRun: String?): List<HermesMessage> {
    if (activeRun == null) return history
    val active = narrations.filter { it.run == activeRun }.distinctBy { it.key }
    val liveRows = history.map { row ->
        val live = active.lastOrNull { it.streamed && it.messageId != null && row.role == "assistant" &&
            row.serverId == narrationMessageId(it.messageId) && narrationCovers(it.text, row.text) }
        if (live != null && live.text.length > row.text.length) row.copy(text = live.text) else row
    }
    val firstNativeTime = liveRows.firstNotNullOfOrNull { it.timestamp.takeIf { _ -> it.serverId > 0 } }
    val overlays = active.filter { note ->
        val anchor = narrationAnchorIndex(liveRows, note)
        // A long-running task can outlive the last-30-message window. Its earlier
        // replayed events belong before that window, never after the newest reply.
        if (firstNativeTime != null && note.timestamp < firstNativeTime && anchor < 0) return@filter false
        val end = if (anchor >= 0) (anchor + 1 until liveRows.size).firstOrNull { liveRows[it].role == "user" } ?: liveRows.size
            else liveRows.indexOfFirst { it.role == "user" && it.timestamp?.let { at -> at > note.timestamp } == true }
                .takeIf { it >= 0 } ?: liveRows.size
        liveRows.withIndex().none { (index, row) -> row.role == "assistant" && (
            note.messageId != null && row.serverId == narrationMessageId(note.messageId) ||
                note.messageId == null && index in (anchor + 1 until end) &&
                    (anchor >= 0 || firstNativeTime != null && row.timestamp != null &&
                        note.userTimestamp?.let { row.timestamp >= it } == true) && narrationCovers(row.text, note.text)) }
    }.sortedWith(compareBy<AssistantNarration> { it.timestamp }.thenBy { it.sequence ?: Long.MAX_VALUE })
    val rows = liveRows.toMutableList()
    overlays.forEach { note ->
        val anchor = narrationAnchorIndex(rows, note)
        val start = (anchor + 1).coerceAtLeast(0)
        val end = (start until rows.size).firstOrNull { rows[it].role == "user" &&
            (anchor >= 0 || rows[it].timestamp?.let { at -> at > note.timestamp } == true) } ?: rows.size
        // Insert only transient rows. Preserve the exact relative order of every native row,
        // including undated rows and timestamps that differ slightly from append order.
        val position = (start until end).firstOrNull { rows[it].timestamp?.let { at -> at > note.timestamp } == true } ?: end
        rows.add(position, HermesMessage("assistant", note.text, localKey = note.key, timestamp = note.timestamp, narration = true))
    }
    return rows
}
