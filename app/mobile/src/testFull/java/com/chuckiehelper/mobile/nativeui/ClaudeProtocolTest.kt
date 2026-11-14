package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ClaudeProtocolTest {
    @Test fun unavailableQuotaIsHiddenWhileAnActualZeroPercentWindowRemainsVisible() {
        assertFalse(claudeQuotaVisible(null))
        assertFalse(claudeQuotaVisible(obj("available" to false, "message" to "当前登录方式不提供额度")))
        assertTrue(claudeQuotaVisible(obj("available" to true, "rateLimitsByLimitId" to obj("claude" to obj("primary" to obj("usedPercent" to 0))))))
        assertEquals("自动批准文件编辑", claudePermissionLabel("acceptEdits"))
        assertEquals("计划模式", claudePermissionLabel("plan"))
    }
    private fun message(id: String, role: String = "assistant", text: String = id, type: String = "") =
        agentHistoryMessage("claude", obj("id" to id, "role" to role, "content" to text, "type" to type, "timestamp" to 1794400000000L))!!

    @Test fun nativeUuidAndToolSummaryIdsSurviveSharedRenderingAndOfflineCache() {
        val rows = listOf(message("uuid-user", "user"), message("uuid-answer"), message("tools-uuid-user", "system", "工具 10 次", "toolSummary"))
        assertEquals(3, rows.map { it.serverId }.distinct().size)
        assertEquals(3, chatMessageKeys(rows).distinct().size)
        assertEquals("tools-uuid-user", rows.last().nativeId)
        assertFalse(rows.last().editable)
        assertEquals(rows, decodeHistorySnapshot(encodeHistorySnapshot(rows)))
    }
    @Test fun nativeQuestionUsesTheSharedQuestionCardRatherThanPlainAssistantText() {
        val row = obj("id" to "tool-question", "role" to "assistant", "type" to "interaction", "content" to "choose",
            "interactions" to JSONArray().put(obj("question" to "choose", "answer" to "A", "status" to "answered")))
        val result = agentHistoryMessage("claude", row)!!
        assertEquals("system", result.role)
        assertEquals("interaction", result.messageType)
        assertEquals("A", result.interactions.single().optString("answer"))
    }
    @Test fun editBoundaryRetainsOriginalUuidEvenWhenNewHistoryArrives() {
        val before = listOf(message("original-user", "user"))
        val boundary = messageEditBoundary(before)
        assertEquals("original-user", boundary.nativeLatestUserId)
        assertEquals("later-user", messageEditBoundary(before + message("later-user", "user")).nativeLatestUserId)
        assertEquals("original-user", boundary.nativeLatestUserId)
    }
    @Test fun earlierPagesKeepNativeOrderWhileRefreshReplacesTheAuthoritativeSuffix() {
        val before = listOf(message("a"), message("b"), message("c"))
        assertEquals(listOf("x", "a", "b", "c"), mergeClaudeHistory(before, listOf(message("x"), message("a")), true).map { it.nativeId })
        assertEquals(listOf("a", "b", "d"), mergeClaudeHistory(before, listOf(message("b"), message("d")), false).map { it.nativeId })
        assertEquals(emptyList<HermesMessage>(), mergeClaudeHistory(before, emptyList(), false))
        assertEquals(listOf("new-chain"), mergeClaudeHistory(before, listOf(message("new-chain")), false).map { it.nativeId })
    }
    @Test fun steeringUsesTheClaudeRunningVocabularyAndRejectsApprovalOrTerminalStates() {
        assertNull(steeringRunPreflightFailure("claude", "running"))
        assertEquals("rejected", steeringRunPreflightFailure("claude", "waiting")?.delivery)
        assertEquals("rejected", steeringRunPreflightFailure("claude", "completed")?.delivery)
        assertNull(steeringRunPreflightFailure("codex", "started"))
    }
    @Test fun takeoverUsesOnlyTheConfirmedProcessAndItsIdleBusyState() {
        val snapshot = obj("available" to true, "running" to true, "pid" to 42, "started_at" to 123, "external_status" to "idle")
        val body = claudeTakeoverRequest(snapshot)
        assertEquals(42, body.getInt("expected_pid")); assertEquals(123, body.getInt("expected_started_at"))
        assertEquals("idle", body.getString("expected_status"))
        assertThrows(IllegalArgumentException::class.java) { claudeTakeoverRequest(obj("available" to false)) }
    }
    @Test fun receiptlessNativeHistoryCannotProveARestoredUnknownSend() {
        val pending = PendingAgentSubmission("key", "hello", emptyList(), 1794400000000L)
        val row = message("native-new", "user", "hello")
        assertEquals(row, currentSubmissionHistoryMatch("claude", pending, emptySet(), listOf(row)))
        assertNull(restoredPendingHistoryMatch("claude", pending, listOf(row)))
        assertNull(currentSubmissionHistoryMatch("claude", pending, setOf(row.serverId), listOf(row)))
    }
    @Test fun uuidHashesNeverBecomeChronologicalSteeringBoundaries() {
        val sent = SteeringMessage("key", "s", "hello", setOf(900L), 900L, "已送达", 1794400000000L)
        val native = message("uuid", "user", "hello").copy(serverId = 2L)
        assertTrue(steeringAppearsInHistory(sent, listOf(native)))
    }
    @Test fun completedSdkTextIsRetainedUntilNativeHistoryTakesOverWithinItsTurn() {
        val user = message("user", "user", "hello")
        val note = AssistantNarration("narration-run-item", "s", "answer", user.serverId, user.text, 1794400001000L,
            userTimestamp = user.timestamp, run = "run", messageId = "sdk-item", streamed = true)
        assertEquals(listOf("hello", "answer"), claudeNarrationOverlay(listOf(user), listOf(note)).map { it.text })
        assertEquals(listOf(note), pendingClaudeNarrations(listOf(user), listOf(note)))
        val native = message("different-native-uuid", text = "answer").copy(timestamp = 1794400001000L)
        assertTrue(pendingClaudeNarrations(listOf(user, native), listOf(note)).isEmpty())
        assertEquals(listOf(user, native), claudeNarrationOverlay(listOf(user, native), listOf(note)))
    }
    @Test fun effortAndQuestionChoicesFollowClaudeContractWithoutWideningCodexOptions() {
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), reasoningChoices("claude", null, "opus"))
        assertTrue(reasoningChoices("codex", null, "unknown").isEmpty())
        assertEquals(listOf("A", "B", "other"), agentQuestionAnswers(obj("multiSelect" to true), "other", listOf("A", "B")))
        assertEquals(listOf("other"), agentQuestionAnswers(obj(), "other", listOf("A", "B")))
    }
}
