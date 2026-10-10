package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Accept the semantic event and the old bridge's misclassified native compaction tool. */
internal fun compactionStreamPhase(agent: String, event: JSONObject): Boolean? {
    if (agent != "codex") return null
    return when (event.optString("type", event.optString("event"))) {
        "context.compaction.started" -> true
        "context.compaction.completed", "context.compaction.failed" -> false
        "tool.started", "tool.completed", "tool.failed" ->
            if (event.optString("tool").equals("contextCompaction", ignoreCase = true))
                event.optString("type", event.optString("event")) == "tool.started" else null
        else -> null
    }
}
