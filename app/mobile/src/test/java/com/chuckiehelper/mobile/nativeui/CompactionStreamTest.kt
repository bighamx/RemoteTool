package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class CompactionStreamTest {
    @Test fun nativeCompactionStartsAndCompletionTriggersHistoryRefresh() {
        assertEquals(true, compactionStreamPhase("codex", obj("type" to "context.compaction.started")))
        assertEquals(false, compactionStreamPhase("codex", obj("type" to "context.compaction.completed")))
        assertEquals(false, compactionStreamPhase("codex", obj("type" to "context.compaction.failed")))
    }
    @Test fun olderBridgeToolClassificationIsRecognizedButActualToolsAreUnchanged() {
        assertEquals(true, compactionStreamPhase("codex", obj("type" to "tool.started", "tool" to "contextCompaction")))
        assertEquals(false, compactionStreamPhase("codex", obj("type" to "tool.completed", "tool" to "contextCompaction")))
        assertNull(compactionStreamPhase("codex", obj("type" to "tool.completed", "tool" to "terminal")))
        assertNull(compactionStreamPhase("hermes", obj("type" to "tool.completed", "tool" to "contextCompaction")))
    }
}
