package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class PendingHistoryAcknowledgementTest {
    private val pending = PendingAgentSubmission("request-key", "继续", emptyList(), 10000)
    @Test fun syntheticToolSummaryIdsCannotBlockHermesSendConfirmation() {
        val summary = HermesMessage("system", "工具 2 次", 9000000000000000L, nativeTurnId = "toolSummary")
        val previous = HermesMessage("user", "继续", 41)
        val delivered = HermesMessage("user", "继续", 42, timestamp = 11000, requestKey = pending.key)
        assertEquals(delivered, currentSubmissionHistoryMatch("hermes", pending, setOf(previous.serverId, summary.serverId), listOf(previous, summary, delivered)))
        assertNull(currentSubmissionHistoryMatch("hermes", pending, setOf(41, 42, summary.serverId), listOf(previous, summary, delivered)))
        assertEquals(delivered, currentSubmissionHistoryMatch("codex", pending, setOf(previous.serverId, summary.serverId), listOf(previous, summary, delivered)))
    }
    @Test fun codexNeedsTheNativeRequestKeyEvenWithRepeatedText() {
        val old = HermesMessage("user", "继续", 41, timestamp = 10900, requestKey = "another-request")
        val current = HermesMessage("user", "继续", 42, timestamp = null, requestKey = pending.key)
        assertNull(currentSubmissionHistoryMatch("codex", pending, emptySet(), listOf(old)))
        assertEquals(current, currentSubmissionHistoryMatch("codex", pending, emptySet(), listOf(old, current)))
        assertEquals(current, restoredPendingHistoryMatch("codex", pending, listOf(old, current)))
    }
    @Test fun oldUnloadedSameTextCannotConfirmOrReceiveNewAttachments() {
        // 时间窗放宽到 -10s 后，10s 内的旧行只能靠 existing（发送时已见过的 serverId）挡住。
        val old = HermesMessage("user", "继续", 41, timestamp = 500)
        assertNull(currentSubmissionHistoryMatch("hermes", pending, setOf(41), listOf(old)))
        val actual = HermesMessage("user", "继续", 42, timestamp = 11000)
        assertEquals(actual, currentSubmissionHistoryMatch("hermes", pending, setOf(41), listOf(old, actual)))
        assertNull(currentSubmissionHistoryMatch("hermes", pending, emptySet(), listOf(actual, actual.copy(serverId = 43))))
    }
    @Test fun hermesRequiresAUserIdAfterTheLastKnownNativeItem() {
        val olderSameText = HermesMessage("user", "继续", 40, timestamp = 11000)
        assertNull(currentSubmissionHistoryMatch("hermes", pending, setOf(41), listOf(olderSameText)))
    }
    @Test fun restoredHermesSubmissionNeedsNativeRunReceipt() {
        val row = HermesMessage("user", "继续", 42, timestamp = 11000)
        assertNull(restoredPendingHistoryMatch("hermes", pending, listOf(row)))
    }
    @Test fun oldOrDuplicateMessagesCannotConfirmANewSubmission() {
        assertNull(restoredPendingHistoryMatch("hermes", pending, listOf(HermesMessage("user", "继续", 1, timestamp = 1000))))
        assertNull(restoredPendingHistoryMatch("hermes", pending, listOf(HermesMessage("user", "继续", 1, timestamp = 11000), HermesMessage("user", "继续", 2, timestamp = 12000))))
        assertNull(restoredPendingHistoryMatch("hermes", pending.copy(timestamp = 0), listOf(HermesMessage("user", "继续", 1, timestamp = 11000))))
    }
    @Test fun wrappersAndAttachmentNotesCannotServeAsARestoredReceipt() {
        val text = "[OUT-OF-BAND USER MESSAGE — direct user message]\n继续\n\n[ChuckieHelper 持久附件]\nfile\n[/OUT-OF-BAND USER MESSAGE]"
        assertNull(restoredPendingHistoryMatch("hermes", pending, listOf(HermesMessage("user", text, 42, timestamp = 11000))))
    }

    @Test fun imageMessagesRequireAttachmentEvidenceAfterNativeIdChanges() {
        val withImage = pending.copy(attachmentIds = listOf("att-1"))
        val row = HermesMessage("user", "继续", 42, timestamp = 11000, attachments = emptyList())
        assertNull(currentSubmissionHistoryMatch("hermes", withImage, setOf(41), listOf(row)))
        val native = row.copy(attachments = listOf(org.json.JSONObject().put("id", "native_42_hash").put("uploadId", "att-1")))
        assertEquals(native, currentSubmissionHistoryMatch("hermes", withImage, setOf(41), listOf(native)))
    }

    @Test fun slowGatewayLandingWithinTenMinutesStillConfirms() {
        val late = HermesMessage("user", "继续", 42, timestamp = 10000 + 480_000)
        assertEquals(late, currentSubmissionHistoryMatch("hermes", pending, setOf(41), listOf(late)))
        val tooLate = HermesMessage("user", "继续", 42, timestamp = 10000 + 700_000)
        assertNull(currentSubmissionHistoryMatch("hermes", pending, setOf(41), listOf(tooLate)))
    }
}
