package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class FinalSyncLedgerTest {
    @Test fun oldCompactionStatusBubbleIsRemovedOnlyAfterRunKindIsConfirmed() {
        val compact = FinalSyncState("session", "codex-compact", null, null, "上下文已压缩", 2_000L, null)
        val answer = compact.copy(run = "codex-answer", item = "answer-item")
        val other = compact.copy(session = "other", run = "other-run")
        val states = restoreFinalSyncStates(JSONArray(listOf(compact.json(), answer.json(), other.json())).toString())
        val history = listOf(HermesMessage("system", "上下文压缩完成", 42,
            timestamp = 1_000L, messageType = "contextCompaction"))
        assertTrue(compact.needsCompactionCheck())
        assertFalse(answer.needsCompactionCheck())
        // A real assistant can use exactly the same words. Never remove it by text.
        assertEquals(states, reconcileCompactionRunSync(states, compact.run,
            obj("status" to "completed", "output" to "上下文已压缩")))
        val cleaned = reconcileCompactionRunSync(states, compact.run,
            obj("kind" to "compact", "status" to "completed", "output" to "上下文已压缩"))
        assertEquals(listOf(answer, other), cleaned)
        assertEquals(history, projectFinalSyncRows(history, cleaned.filterNot { it == answer }, "session"))
        assertEquals(cleaned, restoreFinalSyncStates(JSONArray(cleaned.map { it.json() }).toString()))
        assertEquals(history, projectFinalSyncRows(history, emptyList(), "session"))
    }

    @Test fun failedAndHermesCompactionNeverRepresentAssistantReplies() {
        val pending = FinalSyncState("session", "run", null, null, "", null, null, false)
        for (status in listOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown")) {
            val result = obj("kind" to "compact", "status" to status)
            assertTrue(isCompactionRun("run", result))
            assertEquals(emptyList<FinalSyncState>(), reconcileCompactionRunSync(listOf(pending), "run", result))
        }
        assertTrue(isCompactionRun("hcompact_123", obj("status" to "completed")))
        assertFalse(isCompactionRun("codex_123", obj("status" to "completed", "output" to "上下文已压缩")))
    }

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

    @Test fun failedTurnReceiptDoesNotJoinSeparateProgressMessagesIntoOneBubble() {
        val notes = listOf("我会检查 MCP 的说明。", "已核对：重复 `initialize` 会产生多个身份。\n\n同名连接不合并。",
            "说明已补到手机与 Web。\n\n相关测试均已通过。")
        val rows = listOf(HermesMessage("user", "完善 MCP 说明", 1, timestamp = 1_000L)) +
            notes.mapIndexed { index, text -> HermesMessage("assistant", text, (index + 2).toLong(),
                nativeTurnId = "failed-turn", narration = true, timestamp = 1_100L + index) } +
            HermesMessage("user", "继续", 5, timestamp = 2_000L)
        val receipt = FinalSyncState("session", "failed-run", null, "failed-turn", notes.joinToString(""),
            1_500L, 1_000L, canConfirm = false)
        val restored = restoreFinalSyncStates(JSONArray(listOf(receipt.json())).toString())
        val polluted = rows + HermesMessage("assistant", receipt.text, localKey = "final-sync-failed-run")
        assertTrue(finalSyncAcknowledged(receipt, rows))
        assertEquals(rows, projectFinalSyncRows(polluted, restored, "session"))
        assertEquals(emptyList<FinalSyncState>(), reconcileFinalSync(restored, "session", rows))
        assertEquals(notes, projectFinalSyncRows(rows, restored, "session").filter { it.role == "assistant" }.map { it.text })
    }

    @Test fun concatenationMatchRequiresCompleteOrderedItemsFromTheSameTurn() {
        val rows = listOf(
            HermesMessage("assistant", "第一条\n\n原有段落", 1, nativeTurnId = "turn-a", narration = true),
            HermesMessage("assistant", "第二条 `code`", 2, nativeTurnId = "turn-a", narration = true))
        val receipt = FinalSyncState("session", "run", null, "turn-a", rows.joinToString("") { it.text }, 2_000L, 1_000L)
        assertTrue(finalSyncAcknowledged(receipt, rows))
        assertFalse(finalSyncAcknowledged(receipt, rows.dropLast(1)))
        assertFalse(finalSyncAcknowledged(receipt, rows.asReversed()))
        assertFalse(finalSyncAcknowledged(receipt.copy(turn = "other-turn"), rows))
        assertFalse(finalSyncAcknowledged(receipt.copy(text = receipt.text + "尚未同步的结果"), rows))
        assertFalse(finalSyncAcknowledged(receipt.copy(item = "separate-final-item", canConfirm = false), rows))
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
