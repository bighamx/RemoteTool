package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MessageEditBoundaryTest {
    @Test fun toolSummaryCannotReplaceActualTurnBoundary() {
        val rows = listOf(
            obj("id" to 10, "role" to "user", "content" to "edit me", "turn_id" to "turn1"),
            obj("id" to 11, "role" to "assistant", "content" to "done", "turn_id" to "turn1"),
            obj("id" to 12, "role" to "system", "type" to "toolSummary", "content" to "工具 14 次", "turn_id" to "turn1")
        ).mapNotNull { agentHistoryMessage("codex", it) }
        assertEquals(MessageEditBoundary("turn1", 10), messageEditBoundary(rows))
        assertEquals("toolSummary", rows.last().messageType)
        assertEquals("turn1", rows.last().nativeTurnId)
    }
    @Test fun legacyCachedSummaryMigratesWithoutPoisoningEditBoundary() {
        val legacy = org.json.JSONArray().put(obj("id" to 10, "role" to "user", "text" to "saved", "turnId" to "turn1"))
            .put(obj("id" to 12, "role" to "system", "text" to "工具 14 次", "turnId" to "toolSummary"))
        val rows = decodeHistorySnapshot(legacy)
        assertEquals(MessageEditBoundary("turn1", 10), messageEditBoundary(rows))
        assertEquals("toolSummary", rows.last().messageType)
        assertNull(rows.last().nativeTurnId)
    }
    @Test fun refreshedCachePreservesTypeAndNativeTurnIndependently() {
        val original = HermesMessage("system", "工具 14 次", 12, nativeTurnId = "turn1", editable = false, messageType = "toolSummary")
        assertEquals(original, decodeHistorySnapshot(encodeHistorySnapshot(listOf(original))).single())
    }
    @Test fun dialogBoundaryCannotAdvanceWhenNewHistoryArrives() {
        val before = listOf(HermesMessage("user", "first", 10, nativeTurnId = "turn1"), HermesMessage("assistant", "reply", 11, nativeTurnId = "turn1"))
        val captured = messageEditBoundary(before)
        val after = before + HermesMessage("user", "new", 12, nativeTurnId = "turn2")
        assertEquals(MessageEditBoundary("turn1", 10), captured)
        assertNotEquals(captured, messageEditBoundary(after))
    }
    @Test fun transientNarrationCannotBecomeNativeBoundary() {
        val rows = listOf(HermesMessage("user", "saved", 10, nativeTurnId = "turn1"), HermesMessage("assistant", "live", localKey = "narration-live"))
        assertEquals(MessageEditBoundary("turn1", 10), messageEditBoundary(rows))
    }
    @Test fun codexHistoryRetainsTurnIdentityAndNonEditableSteeringFlag() {
        val row = JSONObject("{\"id\":10,\"role\":\"user\",\"content\":\"steer\",\"turn_id\":\"t1\",\"editable\":false}")
        val message = agentHistoryMessage("codex", row)!!
        assertEquals("t1", message.nativeTurnId)
        assertFalse(message.editable)
    }
}
