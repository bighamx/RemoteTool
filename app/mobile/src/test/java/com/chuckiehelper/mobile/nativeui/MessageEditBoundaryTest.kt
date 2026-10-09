package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class MessageEditBoundaryTest {
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
