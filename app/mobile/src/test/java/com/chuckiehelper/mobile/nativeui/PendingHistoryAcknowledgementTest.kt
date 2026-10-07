package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class PendingHistoryAcknowledgementTest {
    private val pending = PendingAgentSubmission("request-key", "继续", emptyList(), 10000)
    @Test fun restoredHermesSubmissionCanBeConfirmedBySavedHistory() {
        val row = HermesMessage("user", "继续", 42, timestamp = 11000)
        assertEquals(row, restoredPendingHistoryMatch("hermes", pending, listOf(row)))
    }
    @Test fun oldOrDuplicateMessagesCannotConfirmANewSubmission() {
        assertNull(restoredPendingHistoryMatch("hermes", pending, listOf(HermesMessage("user", "继续", 1, timestamp = 1000))))
        assertNull(restoredPendingHistoryMatch("hermes", pending, listOf(HermesMessage("user", "继续", 1, timestamp = 11000), HermesMessage("user", "继续", 2, timestamp = 12000))))
        assertNull(restoredPendingHistoryMatch("hermes", pending.copy(timestamp = 0), listOf(HermesMessage("user", "继续", 1, timestamp = 11000))))
    }
    @Test fun wrappersAndAttachmentNotesDoNotHideDeliveredText() {
        val text = "[OUT-OF-BAND USER MESSAGE — direct user message]\n继续\n\n[ChuckieHelper 持久附件]\nfile\n[/OUT-OF-BAND USER MESSAGE]"
        assertNotNull(restoredPendingHistoryMatch("hermes", pending, listOf(HermesMessage("user", text, 42, timestamp = 11000))))
    }
}
