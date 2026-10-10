package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class NativeCompactionHistoryTest {
    @Test fun nativeOrderSurvivesMissingTimesAndLegacyOverlays() {
        val native = listOf(
            obj("id" to 90, "role" to "user", "content" to "继续"),
            obj("id" to 2, "role" to "system", "type" to "contextCompaction", "content" to "上下文压缩完成"),
            obj("id" to 70, "role" to "assistant", "content" to "完成", "timestamp" to 1791590000000L)
        ).mapNotNull { agentHistoryMessage("codex", it) }
        assertEquals(listOf(90L, 2L, 70L), native.map { it.serverId })
        assertNull(native[1].timestamp)
        assertFalse(native[1].editable)
        val stale = HermesMessage("system", "上下文压缩完成", localKey = "compaction-old", timestamp = 1L)
        val displayed = mergeAgentCompactionNotices("codex", native + stale,
            listOf(AgentCompactionNotice("old", "session", 1L)))
        assertEquals(native, displayed)
        assertEquals(native, mergeAgentCompactionNotices("codex", displayed, emptyList()))
    }

    @Test fun completionTimeComesFromServerAndOtherSystemItemsStayHidden() {
        val row = obj("id" to 4, "role" to "system", "type" to "contextCompaction",
            "content" to "上下文压缩完成", "timestamp" to 1791590000000L)
        assertEquals(1791590000000L, agentHistoryMessage("codex", row)!!.timestamp)
        assertNull(agentHistoryMessage("codex", obj("role" to "system", "content" to "internal prompt")))
    }
}
