package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class VisibleAgentMessageTest {
    @Test fun captionlessUserAndAssistantAttachmentsRemainVisible() {
        assertTrue(visibleAgentMessage("user", "", 1))
        assertTrue(visibleAgentMessage("assistant", "null", 1))
        assertFalse(visibleAgentMessage("assistant", "null", 0))
        assertFalse(visibleAgentMessage("tool", "output", 1))
        assertTrue(visibleAgentMessage("assistant", "回复", 0))
    }
}
