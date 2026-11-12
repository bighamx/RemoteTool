package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Takeover must target the exact desktop process and state the user confirmed. */
internal fun claudeTakeoverRequest(activity: JSONObject?): JSONObject {
    require(activity?.optBoolean("available") == true && activity.optBoolean("running") && activity.optLong("pid") > 1 && activity.optLong("started_at") > 0) { "占用信息已失效，请刷新后重新确认" }
    return obj("expected_pid" to activity.optLong("pid"), "expected_started_at" to activity.optLong("started_at"),
        "expected_status" to activity.cleanSetting("external_status"))
}

internal fun mergeClaudeHistory(previous: List<HermesMessage>, incoming: List<HermesMessage>, earlier: Boolean): List<HermesMessage> {
    if (earlier) return (incoming + previous).distinctBy { it.nativeId }
    if (incoming.isEmpty()) return emptyList()
    val first = previous.indexOfFirst { it.nativeId == incoming.first().nativeId }
    // Only a shared native anchor proves that an older prefix still belongs to this chain.
    return (if (first >= 0) previous.take(first) + incoming else incoming).distinctBy { it.nativeId }
}

/** SDK stream item IDs differ from transcript UUIDs; reconcile text within its user-turn boundary. */
internal fun claudeNarrationOverlay(history: List<HermesMessage>, notes: List<AssistantNarration>): List<HermesMessage> =
    notes.groupBy { it.run }.entries.fold(history) { rows, (run, group) ->
        withActiveNarrations(rows, group.map { it.copy(messageId = null) }, run)
    }

internal fun pendingClaudeNarrations(history: List<HermesMessage>, notes: List<AssistantNarration>): List<AssistantNarration> {
    val pendingKeys = claudeNarrationOverlay(history, notes).filter { it.serverId == 0L }.map { it.localKey }.toSet()
    return notes.filter { it.key in pendingKeys }
}
