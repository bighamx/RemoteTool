package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject

internal data class FinalSyncState(val session: String, val run: String, val item: String?, val turn: String?,
    val text: String, val timestamp: Long?, val startedAt: Long?, val canConfirm: Boolean = true,
    val flushedPrefix: String = "") {
    fun json() = JSONObject().put("session", session).put("run", run).put("item", item).put("turn", turn)
        .put("text", text).put("timestamp", timestamp).put("startedAt", startedAt).put("canConfirm", canConfirm)
        .put("flushedPrefix", flushedPrefix)
}

internal data class OutgoingFiles(val session: String, val run: String, val item: String?, val turn: String?,
    val text: String, val startedAt: Long?, val ids: List<String>, val messageKey: String = "",
    val discoveryUntil: Long = 0L, val canBind: Boolean = true) {
    fun json() = JSONObject().put("session", session).put("run", run).put("item", item).put("turn", turn)
        .put("text", text).put("startedAt", startedAt).put("ids", JSONArray(ids)).put("messageKey", messageKey)
        .put("discoveryUntil", discoveryUntil).put("canBind", canBind)
}

internal fun restoreFinalSyncStates(raw: String?): List<FinalSyncState> = runCatching {
    val rows = JSONArray(raw ?: "[]")
    (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        val session = row.optString("session"); val run = row.optString("run")
        if (session.isBlank() || run.isBlank()) null else FinalSyncState(session, run,
            row.optString("item").takeUnless { it.isBlank() || it == "null" },
            row.optString("turn").takeUnless { it.isBlank() || it == "null" }, row.optString("text"),
            row.optLong("timestamp").takeIf { it > 0 }, row.optLong("startedAt").takeIf { it > 0 },
            row.optBoolean("canConfirm", true), row.optString("flushedPrefix"))
    }
}.getOrDefault(emptyList())

internal fun restoreOutgoingFiles(raw: String?): List<OutgoingFiles> = runCatching {
    val rows = JSONArray(raw ?: "[]")
    (0 until rows.length()).mapNotNull { index ->
        val row = rows.getJSONObject(index)
        val session = row.optString("session"); val run = row.optString("run")
        val ids = row.optJSONArray("ids") ?: JSONArray()
        if (session.isBlank() || run.isBlank() || ids.length() == 0 && row.optString("messageKey").isBlank()) null
        else OutgoingFiles(session, run,
            row.optString("item").takeUnless { it.isBlank() || it == "null" },
            row.optString("turn").takeUnless { it.isBlank() || it == "null" }, row.optString("text"),
            row.optLong("startedAt").takeIf { it > 0 }, (0 until ids.length()).map { ids.getString(it) },
            row.optString("messageKey"), row.optLong("discoveryUntil"), row.optBoolean("canBind", true))
    }
}.getOrDefault(emptyList())

/** A terminal status snapshot is cumulative. After a gap it supersedes any partial stream prefix. */
internal fun terminalSyncText(stream: String, phase: String, gap: Boolean, output: String, flushedPrefix: String): String {
    val tail = output.removePrefix(flushedPrefix)
    if (tail.isBlank()) return stream
    return if (gap || stream.isBlank() || phase != "final_answer" || tail.length >= stream.length) tail else stream
}

// Older bridge versions exposed every commentary item plus the final answer as
// one run.output. The native history already has those items separately.
internal fun cumulativeRunOutputAlreadyInHistory(sync: FinalSyncState, history: List<HermesMessage>): Boolean {
    if (sync.item != null || sync.turn == null || sync.text.isBlank()) return false
    val items = history.filter { it.role == "assistant" && it.nativeTurnId == sync.turn }
    return items.size > 1 && items.joinToString("\n\n") { it.text }.trim() == sync.text.trim()
}

internal fun finalSyncAcknowledged(sync: FinalSyncState, history: List<HermesMessage>): Boolean =
    cumulativeRunOutputAlreadyInHistory(sync, history) ||
        sync.canConfirm && terminalHistoryContainsAssistant(history, sync.item, sync.text, sync.turn, sync.startedAt)

internal fun retainFinalSync(states: List<FinalSyncState>, incoming: FinalSyncState): List<FinalSyncState> =
    states.filterNot { it.run == incoming.run } + incoming

/** Old versions mistook the compact operation's status output for an assistant answer.
 * Text only selects candidates; the run endpoint must confirm the operation kind. */
internal fun FinalSyncState.needsCompactionCheck(): Boolean =
    item == null && text.trim() in setOf("上下文已压缩", "上下文压缩完成")

internal fun reconcileCompactionRunSync(states: List<FinalSyncState>, run: String,
    result: JSONObject): List<FinalSyncState> =
    if (isCompactionRun(run, result)) states.filterNot { it.run == run } else states

internal fun reconcileFinalSync(states: List<FinalSyncState>, session: String,
    history: List<HermesMessage>): List<FinalSyncState> =
    states.filterNot { it.session == session && finalSyncAcknowledged(it, history) }

internal fun projectFinalSyncRows(rows: List<HermesMessage>, states: List<FinalSyncState>, session: String): List<HermesMessage> {
    val result = rows.filterNot { it.localKey?.startsWith("final-sync-") == true }.toMutableList()
    states.filter { it.session == session && it.text.isNotBlank() }.sortedBy { it.timestamp ?: Long.MAX_VALUE }.forEach { sync ->
        if (cumulativeRunOutputAlreadyInHistory(sync, result)) return@forEach
        val native = exactFinalAssistant(result, sync.item, sync.turn, sync.text, sync.startedAt)
        if (native != null) {
            if (native.text.trim() == sync.text.trim()) return@forEach
            if (sync.text.startsWith(native.text)) {
                val position = result.indexOf(native)
                result[position] = native.copy(text = sync.text)
                return@forEach
            }
        }
        val partial = result.filter { row -> row.role == "assistant" && !row.narration &&
            (sync.turn != null && row.nativeTurnId == sync.turn ||
                sync.turn == null && sync.startedAt != null && row.timestamp?.let { it >= sync.startedAt } == true) &&
            row.text.isNotEmpty() && sync.text.startsWith(row.text) }.singleOrNull()
        if (partial != null) {
            result[result.indexOf(partial)] = partial.copy(text = sync.text)
            return@forEach
        }
        val pending = HermesMessage("assistant", sync.text, localKey = "final-sync-${sync.run}",
            timestamp = sync.timestamp, editable = false)
        val next = sync.timestamp?.let { time -> result.indexOfFirst { it.timestamp?.let { at -> at > time } == true } } ?: -1
        if (next >= 0) result.add(next, pending) else result.add(pending)
    }
    return result
}
