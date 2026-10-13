package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class FinalSyncLedgerTest {
    @Test fun cumulativeRunOutputDoesNotRepeatNativeCommentary() {
        val rows = listOf(
            HermesMessage("assistant", "先检查改动", narrationMessageId("note-a"), nativeTurnId = "turn-a", narration = true),
            HermesMessage("assistant", "测试通过", narrationMessageId("note-b"), nativeTurnId = "turn-a", narration = true),
            HermesMessage("assistant", "已提交推送", narrationMessageId("final"), nativeTurnId = "turn-a"))
        val cumulative = FinalSyncState("session", "run-a", null, "turn-a",
            rows.joinToString("\n\n") { it.text }, 2_000L, 1_000L)
        assertTrue(finalSyncAcknowledged(cumulative, rows))
        assertEquals(rows, projectFinalSyncRows(rows, listOf(cumulative), "session"))
        assertEquals(emptyList<FinalSyncState>(), reconcileFinalSync(listOf(cumulative), "session", rows))
    }

    @Test fun emptyStreamCannotProveFinalHistoryIsComplete() {
        val commentary = HermesMessage("assistant", "正在检查", narrationMessageId("commentary"),
            nativeTurnId = "turn-a", timestamp = 1_100L)
        assertFalse(terminalHistoryContainsAssistant(listOf(commentary), "commentary", "", "turn-a", 1_000L))
        assertFalse(terminalHistoryContainsAssistant(emptyList(), null, "", "turn-a", 1_000L))
        assertEquals("最终回答", terminalSyncText("", "commentary", false, "正在检查最终回答", "正在检查"))
    }

    @Test fun gapRebuildsFullTextAndDoesNotConfirmAStoredPrefix() {
        val complete = terminalSyncText("开头", "final_answer", true, "开头中间结尾", "")
        assertEquals("开头中间结尾", complete)
        val partial = HermesMessage("assistant", "开头", narrationMessageId("item-a"),
            nativeTurnId = "turn-a", timestamp = 1_200L)
        assertFalse(terminalHistoryContainsAssistant(listOf(partial), "item-a", complete, "turn-a", 1_000L))
        val sync = FinalSyncState("session", "run-a", "item-a", "turn-a", complete, 1_200L, 1_000L)
        assertEquals(listOf(complete), projectFinalSyncRows(listOf(partial), listOf(sync), "session").map { it.text })
        assertTrue(terminalHistoryContainsAssistant(listOf(partial.copy(text = complete)), "item-a", complete, "turn-a", 1_000L))
        val uncertain = sync.copy(text = "开头", canConfirm = false)
        assertFalse(finalSyncAcknowledged(uncertain, listOf(partial)))
        assertEquals(uncertain, restoreFinalSyncStates(JSONArray(listOf(uncertain.json())).toString()).single())
    }

    @Test fun priorFinalSurvivesTheNextUserMessageAndProcessRestore() {
        val a = FinalSyncState("session", "run-a", null, "turn-a", "第一轮尾段", 2_000L, 1_000L)
        val b = FinalSyncState("session", "run-b", null, "turn-b", "第二轮尾段", 4_000L, 3_000L)
        val restored = restoreFinalSyncStates(JSONArray(listOf(a.json(), b.json())).toString())
        assertEquals(listOf(a, b), restored)
        val rows = listOf(HermesMessage("user", "第二轮问题", timestamp = 3_000L))
        assertEquals(listOf("第一轮尾段", "第二轮问题", "第二轮尾段"),
            projectFinalSyncRows(rows, restored, "session").map { it.text })
        assertEquals(3, projectFinalSyncRows(projectFinalSyncRows(rows, restored, "session"), restored, "session").size)
        val afterFirstReply = reconcileFinalSync(restored, "session",
            listOf(HermesMessage("assistant", "第一轮尾段", 91, nativeTurnId = "turn-a")))
        assertEquals(listOf(b), afterFirstReply)
        assertEquals(listOf(b), retainFinalSync(afterFirstReply, b))
    }

    @Test fun twoRunsRetainSeparateFileReceiptsAcrossRestart() {
        val a = OutgoingFiles("session", "run-a", "item-a", "turn-a", "A", 1_000L, listOf("file-a"))
        val b = OutgoingFiles("session", "run-b", "item-b", "turn-b", "B", 2_000L, listOf("file-b"))
        val restored = restoreOutgoingFiles(JSONArray(listOf(a.json(), b.json())).toString())
        assertEquals(listOf(a, b), restored)
        val history = listOf(HermesMessage("assistant", "A", narrationMessageId("item-a")),
            HermesMessage("assistant", "B", narrationMessageId("item-b")))
        assertEquals(history[0], exactFinalAssistant(history, restored[0].item, restored[0].turn, restored[0].text))
        assertEquals(history[1], exactFinalAssistant(history, restored[1].item, restored[1].turn, restored[1].text))
    }

    @Test fun pendingCatalogDiscoveryKeepsItsRequestKeyAcrossRestart() {
        val pending = OutgoingFiles("session", "run-a", null, "turn-a", "结果", 1_000L,
            emptyList(), "request-a", 5_000L)
        assertEquals(listOf(pending), restoreOutgoingFiles(JSONArray(listOf(pending.json())).toString()))
    }
}
