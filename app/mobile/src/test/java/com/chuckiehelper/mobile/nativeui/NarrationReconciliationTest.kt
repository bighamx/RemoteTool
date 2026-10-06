package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class NarrationReconciliationTest {
    private val user = HermesMessage("user", "检查设备", serverId = 1, timestamp = 100)
    private fun note(key: String, text: String, at: Long = 110) = AssistantNarration("narration-$key", "session", text, 1, user.text, at, run = "run")

    @Test fun hermesInterimRemainsVisibleBeforeTheUserIsPersistedAndAfterAcknowledgement() {
        val optimistic = HermesMessage("user", "检查设备", localKey = "submission", timestamp = 100)
        val interim = assistantNarrationEvent("narration-interim", "session", "先检查部署配置", 110,
            listOf(optimistic), 22, "submission")!!.copy(run = "run", streamed = true)
        val live = mergeAssistantNarrations(listOf(optimistic), listOf(interim))
        assertEquals(listOf(optimistic.text, interim.text), live.map { it.text })
        // The tool clears pendingText, but its finished commentary stays in the list.
        assertEquals(live, mergeAssistantNarrations(live, listOf(interim)))
        val acknowledged = user.copy(serverId = 42)
        val bound = acknowledgeNarrationUser(listOf(interim), "session", "submission", acknowledged)
        val refreshed = mergeAssistantNarrations(listOf(acknowledged), bound)
        assertEquals(live.map { it.text }, refreshed.map { it.text })
        assertEquals(live[1].localKey, refreshed[1].localKey)
    }
    @Test fun explicitRunUserBoundarySurvivesClockSkewAndDoesNotAttachToAnotherOptimisticUser() {
        val optimistic = user.copy(serverId = 0, localKey = "send-a", timestamp = 500)
        val next = optimistic.copy(localKey = "send-b", text = "另一个问题", timestamp = 600)
        val interim = assistantNarrationEvent("narration-live", "session", "正在检查", 110,
            listOf(optimistic, next), userKey = "send-a")!!
        assertEquals(listOf(user.text, interim.text, next.text),
            mergeAssistantNarrations(listOf(optimistic, next), listOf(interim)).map { it.text })
        assertEquals(listOf(next), mergeAssistantNarrations(listOf(next), listOf(interim)))
    }
    @Test fun slowerHistoryDoesNotEraseAnInFlightNativeItemOrSuppressItsText() {
        val native = HermesMessage("assistant", "先检查", serverId = narrationMessageId("item"), narration = true)
        val text = "先检查部署配置，再验证服务"
        assertEquals(text, visiblePendingAssistant(listOf(user, native), "item", text))
        val reconciled = reconcilePendingAssistant(listOf(user, native), "item", text)
        assertEquals(text, reconciled.last().text)
        assertEquals("", visiblePendingAssistant(reconciled, "item", text))
        assertEquals(native.serverId, reconciled.last().serverId)
    }
    @Test fun streamNarrationArrivingDuringHistoryEnrichmentSurvivesPublication() {
        val snapshot = listOf(user)
        // A GET starts; two interim/tool boundaries arrive while its attachments are fetched.
        val first = note("first-live", "先检查部署配置").copy(streamed = true)
        val second = note("second-live", "接着验证服务状态", 120).copy(streamed = true)
        val notesAtPublication = listOf(first, second)
        val published = mergeAssistantNarrations(snapshot, notesAtPublication)
        assertEquals(listOf(user.text, first.text, second.text), published.map { it.text })
        assertEquals(published, mergeAssistantNarrations(snapshot, notesAtPublication))
    }

    @Test fun cumulativeInterimUpdatesKeepOneBubbleAndItsFirstKey() {
        var rows = listOf(note("first", "先检查部署配置").copy(streamed = true))
        rows = upsertAssistantNarration(rows, note("later", "先检查部署配置，再验证服务状态", 120).copy(streamed = true), listOf(user))
        assertEquals(1, rows.size)
        assertEquals("narration-first", rows.single().key)
        assertEquals(110L, rows.single().timestamp)
        assertEquals("先检查部署配置，再验证服务状态", rows.single().text)
    }
    @Test fun realAssistantTextWinsOverFlattenedCommandComment() {
        val command = note("tool", "先检查部署配置，再验证服务状态：ssh office \"dir /b\"")
        val actual = note("interim", "先检查部署配置，再验证服务状态", 120).copy(streamed = true)
        val rows = upsertAssistantNarration(listOf(command), actual, listOf(user))
        assertEquals(1, rows.size)
        assertEquals(actual.text, rows.single().text)
    }
    @Test fun replayedAndContainedCopiesCollapseWithinTheirOwnUserWindow() {
        val short = note("short", "先检查部署配置")
        val full = note("full", "先检查部署配置，然后验证服务", 120)
        val rows = mergeAssistantNarrations(listOf(user), listOf(short, full, full))
        assertEquals(listOf(user.text, full.text), rows.map { it.text })
        assertEquals(short.key, rows[1].localKey)
    }
    @Test fun whitespaceAndHeadingFormattingDoNotCreateAnotherBubble() {
        val canonical = HermesMessage("assistant", "# 先检查部署配置\n\n然后验证服务", serverId = 2, timestamp = 120, narration = true)
        assertEquals(2, mergeAssistantNarrations(listOf(user, canonical), listOf(note("copy", "先检查部署配置 然后验证服务"))).size)
        assertTrue(narrationCovers("# 先看看配置，再验证", "先看看配置"))
    }
    @Test fun nativeCumulativeNarrationRowsCollapseButFinalAnswerStaysSeparate() {
        val first = HermesMessage("assistant", "先检查部署配置", serverId = 2, timestamp = 110, narration = true)
        val expanded = first.copy(serverId = 3, text = "先检查部署配置，然后验证服务", timestamp = 120)
        val final = HermesMessage("assistant", "先检查部署配置，然后验证服务。结果：一切正常", serverId = 4, timestamp = 130)
        val rows = mergeAssistantNarrations(listOf(user, first, expanded, final), emptyList())
        assertEquals(3, rows.size)
        assertEquals(2L, rows[1].serverId)
        assertEquals(final, rows.last())
    }
    @Test fun completingAndLoadingCanonicalHistoryKeepTheSameNarrationKey() {
        val note = note("live", "先检查部署配置").copy(streamed = true, messageId = "native-item")
        val live = mergeAssistantNarrations(listOf(user), listOf(note))
        val canonical = HermesMessage("assistant", note.text, serverId = narrationMessageId("native-item"), timestamp = 110, narration = true)
        val loaded = mergeAssistantNarrations(listOf(user, canonical), listOf(note))
        assertEquals(live[1].localKey, loaded[1].localKey)
        assertEquals(live.map { it.text }, loaded.map { it.text })
        assertEquals(loaded, mergeAssistantNarrations(loaded, listOf(note)))
    }
    @Test fun explicitItemRevisionsReplaceContentInsteadOfAppending() {
        val first = note("a", "初始说法").copy(messageId = "item", streamed = true)
        val revised = first.copy(text = "修改后的说法", timestamp = 120)
        val rows = upsertAssistantNarration(listOf(first), revised, listOf(user))
        assertEquals(1, rows.size)
        assertEquals(revised.text, rows.single().text)
    }
    @Test fun repeatedNarrationInDifferentUserTurnsIsNotDropped() {
        val next = user.copy(serverId = 3, timestamp = 200, text = "再检查另一台")
        val first = note("a", "先检查部署配置")
        val second = note("b", first.text, 210).copy(anchor = 3, userText = next.text)
        val history = listOf(user, next)
        val notes = upsertAssistantNarration(listOf(first), second, history)
        assertEquals(2, notes.size)
        assertEquals(listOf(user.text, first.text, next.text, second.text), mergeAssistantNarrations(history, notes).map { it.text })
    }
    @Test fun codexPhaseMarksNarrationWithoutChangingFinalAnswers() {
        assertTrue(isAssistantNarration(obj("role" to "assistant", "phase" to "commentary")))
        assertFalse(isAssistantNarration(obj("role" to "assistant", "phase" to "final_answer")))
    }
    @Test fun replayedNativeItemUsesItsExactOriginalPositionEvenAfterInterjection() {
        val canonical = HermesMessage("assistant", "先检查部署配置", serverId = narrationMessageId("old-item"), timestamp = 110, narration = true)
        val next = user.copy(serverId = 3, timestamp = 200, text = "稍后再检查")
        val replay = note("replay", canonical.text, 250).copy(messageId = "old-item", streamed = true)
        val history = listOf(user, canonical, next)
        assertEquals(0, narrationAnchorIndex(history, replay))
        val rows = mergeAssistantNarrations(history, listOf(replay))
        assertEquals(listOf(user.text, canonical.text, next.text), rows.map { it.text })
        assertEquals(110L, rows[1].timestamp)
    }
    @Test fun olderHistoriesWithoutPhaseStayCoalescedAfterStreamIdentityReconciliation() {
        val first = note("one", "先检查部署配置").copy(streamed = true, messageId = "one")
        val second = note("two", "先检查部署配置，然后验证服务", 120).copy(streamed = true, messageId = "two")
        val history = listOf(user,
            HermesMessage("assistant", first.text, serverId = narrationMessageId("one"), timestamp = 110),
            HermesMessage("assistant", second.text, serverId = narrationMessageId("two"), timestamp = 120))
        val merged = mergeAssistantNarrations(history, listOf(first, second))
        assertEquals(listOf(user.text, second.text), merged.map { it.text })
        assertEquals(first.key, merged[1].localKey)
        assertEquals(merged, mergeAssistantNarrations(merged, listOf(first, second)))
    }
}
