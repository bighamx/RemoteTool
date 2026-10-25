package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class AssistantNarrationTest {
    @Test fun explanatoryCommentIsSeparatedFromTheCommand() {
        assertEquals("先摸清 office 上的 ChuckieHelper 部署形态", terminalNarration("terminal", "# 先摸清 office 上的 ChuckieHelper 部署形态\nssh office \"dir /b D:\\GIT\""))
        assertEquals("先摸清 office 上的 ChuckieHelper 部署形态： ssh office \"dir /b D:\\GIT\"", terminalNarration("terminal", "# 先摸清 office 上的 ChuckieHelper 部署形态： ssh office \"dir /b D:\\GIT\""))
    }
    @Test fun commandsToolResultsAndShebangsAreNotInventedAsModelMessages() {
        assertNull(terminalNarration("terminal", "ssh office \"dir /b\""))
        assertNull(terminalNarration("search", "# result title"))
        assertNull(terminalNarration("terminal", "#!/bin/sh\necho example"))
        assertNull(terminalNarration("terminal", "#include <stdio.h>"))
    }
    @Test fun multipleLeadingCommentsStayReadable() {
        assertEquals("先检查配置\n然后验证服务", terminalNarration("终端", "# 先检查配置\n# 然后验证服务\ndotnet build\n# 不是前置说明"))
    }
    @Test fun narrationSurvivesHistoryRefreshBeforeTheFinalReplyAndDoesNotDuplicate() {
        val user = HermesMessage("user", "部署 office", serverId = 1, timestamp = 100)
        val final = HermesMessage("assistant", "部署完成", serverId = 2, timestamp = 200)
        val note = AssistantNarration("narration-run-1", "A", "先摸清部署形态", 1, user.text, 123)
        val merged = mergeAssistantNarrations(listOf(user, final), listOf(note))
        assertEquals(listOf(user.text, note.text, final.text), merged.map { it.text })
        assertEquals(merged, mergeAssistantNarrations(merged, listOf(note)))
        assertEquals(123L, merged[1].timestamp)
        val canonical = final.copy(text = note.text + "\n\n部署完成")
        assertEquals(listOf(user, canonical), mergeAssistantNarrations(listOf(user, canonical), listOf(note)))
    }
    @Test fun aNewInstructionCannotReceiveNarrationFromThePreviousInstruction() {
        val first = HermesMessage("user", "部署 office", serverId = 1, timestamp = 100)
        val second = HermesMessage("user", "检查 home", serverId = 3, timestamp = 200)
        val final = HermesMessage("assistant", "检查完毕", serverId = 4, timestamp = 300)
        val note = AssistantNarration("narration-run-1", "A", "先摸清部署形态", 1, first.text, 123)
        assertEquals(listOf(first.text, note.text, second.text, final.text), mergeAssistantNarrations(listOf(first, second, final), listOf(note)).map { it.text })
    }
    @Test fun hundredCharactersAreKeptAndTheRestOfThatRunIsReplacedByOneEllipsis() {
        assertEquals("a".repeat(100), truncateNarration("a".repeat(100)))
        assertEquals("a".repeat(100) + "…", truncateNarration("a".repeat(101)))
        assertEquals("a".repeat(100) + "…", truncateNarration("a".repeat(3000)))
    }
    @Test fun ChineseRestartsTheLimitForEachIndependentRun() {
        assertEquals("前" + "x".repeat(100) + "…中" + "y".repeat(100) + "…后",
            truncateNarration("前" + "x".repeat(150) + "中" + "y".repeat(180) + "后"))
        assertEquals("中文说明无需截断", truncateNarration("中文说明无需截断"))
    }
    @Test fun PathsSpacesAndLineBreaksBelongToTheSameNonChineseRun() {
        val code = "C:\\Users\\user\\AppData\\Local\\OpenAI\\Codex\\bin\npython import subprocess".repeat(3)
        assertEquals("路径" + code.take(100) + "…接着说明", truncateNarration("路径${code}接着说明"))
    }
    @Test fun EmojiAndSupplementaryHanAreCountedWithoutSplittingSurrogates() {
        val emoji = "\uD83D\uDE42"
        val han = "\uD840\uDC00"
        assertEquals(emoji.repeat(100) + "…" + han + emoji.repeat(100) + "…",
            truncateNarration(emoji.repeat(140) + han + emoji.repeat(140)))
    }
    @Test fun KnownNarrationIsShortenedWithoutEditingTheFinalReplyOrTheRawText() {
        val note = "先检查" + "x".repeat(140) + "再继续"
        val final = note + "\n\n完整结果\n" + "y".repeat(80)
        assertEquals("先检查" + "x".repeat(100) + "…再继续\n\n完整结果\n" + "y".repeat(80),
            displayNarration(final, false, listOf(note)))
        assertEquals(final, displayNarration(final, false))
        assertEquals(140, note.count { it == 'x' })
        val clipped = displayNarration(final, false, listOf(note))
        assertEquals(clipped, displayNarration(clipped, false, listOf(note)))
    }
    @Test fun HistoryUsesToolMetadataInsteadOfGuessingFromCodeKeywords() {
        val row = org.json.JSONObject("""{"role":"assistant","tool_calls":[{"type":"function"}]}""")
        assertTrue(isAssistantNarration(row))
        row.put("tool_calls", row.getJSONArray("tool_calls").toString())
        assertTrue(isAssistantNarration(row))
        row.put("role", "user")
        assertFalse(isAssistantNarration(row))
        assertFalse(isAssistantNarration(org.json.JSONObject("""{"role":"assistant","content":"python import subprocess","tool_calls":[]}""")))
    }
    @Test fun FlattenedCommandsKeepChineseAfterLongScriptRuns() {
        val preview = "# 先检查 office 的配置：" + "python import subprocess ".repeat(20) + "然后验证服务"
        val raw = terminalNarration("terminal", preview)!!
        assertTrue(raw.endsWith("然后验证服务"))
        assertTrue(displayNarration(raw, true).endsWith("…然后验证服务"))
        assertTrue(raw.length > displayNarration(raw, true).length)
    }
    private fun history() = listOf(
        HermesMessage("user", "继续", serverId = 900, timestamp = 1000),
        HermesMessage("assistant", "旧结果", serverId = 50, timestamp = 1500),
        HermesMessage("user", "继续", serverId = 2, timestamp = 2000),
        HermesMessage("assistant", "新结果", serverId = 1, timestamp = 2500),
    )
    @Test fun aReplayedOldEventBelongsToItsOriginalInstruction() {
        val original = history()
        val note = assistantNarrationEvent("narration-run-5", "A", "旧旁白", 1200, original, 5)!!
        assertEquals(900L, note.anchor)
        assertEquals(1000L, note.userTimestamp)
        assertEquals(listOf("继续", "旧旁白", "旧结果", "继续", "新结果"),
            mergeAssistantNarrations(original, listOf(note)).map { it.text })
    }
    @Test fun timestampRepairsAnAlreadyWrongLatestAnchor() {
        val original = history()
        val wrong = AssistantNarration("narration-wrong", "A", "旧旁白", 2, "继续", 1200)
        val polluted = original + HermesMessage("assistant", wrong.text, localKey = wrong.key, timestamp = wrong.timestamp)
        val corrected = mergeAssistantNarrations(polluted, listOf(wrong))
        assertEquals(listOf("继续", "旧旁白", "旧结果", "继续", "新结果"), corrected.map { it.text })
        assertEquals(corrected, mergeAssistantNarrations(corrected, listOf(wrong)))
    }
    @Test fun anExpiredAnchorNeverFallsBackToTheLatestRepeatedText() {
        val missing = AssistantNarration("narration-missing", "A", "旧旁白", 77, "继续", 1200)
        assertEquals(0, narrationAnchorIndex(history(), missing))
        val onlyNew = history().drop(2)
        assertEquals(-1, narrationAnchorIndex(onlyNew, missing))
        assertEquals(onlyNew, mergeAssistantNarrations(onlyNew, listOf(missing)))
    }
    @Test fun missingEventTimeIsNotReplacedWithReceiptTime() {
        assertNull(assistantNarrationEvent("narration-unknown", "A", "无时间旁白", null, history()))
        assertNull(assistantNarrationEvent("narration-zero", "A", "无时间旁白", 0, history()))
    }
    @Test fun optimisticUserRowsBlockWrongPlacementUntilCanonicalHistoryArrives() {
        val before = history().take(2) + HermesMessage("user", "新问题", localKey = "sending", timestamp = 1800)
        val note = assistantNarrationEvent("narration-pending", "A", "新旁白", 1900, before)!!
        assertEquals(0L, note.anchor)
        assertEquals(before, mergeAssistantNarrations(before, listOf(note)))
        val confirmed = before.dropLast(1) + before.last().copy(serverId = 42, localKey = null)
        assertEquals(listOf("继续", "旧结果", "新问题", "新旁白"), mergeAssistantNarrations(confirmed, listOf(note)).map { it.text })
    }
    @Test fun reversedReplayOrderIsSortedInsideItsOwnUserWindow() {
        val early = AssistantNarration("narration-early", "A", "早旁白", 900, "继续", 1100, sequence = 1)
        val middle = AssistantNarration("narration-middle", "A", "后旁白", 900, "继续", 1600, sequence = 2)
        val new = AssistantNarration("narration-new", "A", "新旁白", 2, "继续", 2200, sequence = 3)
        assertEquals(listOf("继续", "早旁白", "旧结果", "后旁白", "继续", "新旁白", "新结果"),
            mergeAssistantNarrations(history(), listOf(new, middle, early)).map { it.text })
    }
    @Test fun eventSequenceMakesReplayIdempotentAndPreservesEqualTimeOrdering() {
        val first = assistantNarrationEvent("narration-run-1", "A", "第一段", 1200, history(), 1)!!
        val second = assistantNarrationEvent("narration-run-2", "A", "第二段", 1200, history(), 2)!!
        val once = mergeAssistantNarrations(history(), listOf(second, first, first))
        assertEquals(listOf("继续", "第一段", "第二段", "旧结果", "继续", "新结果"), once.map { it.text })
        assertEquals(once, mergeAssistantNarrations(once, listOf(second, first, first)))
    }
    @Test fun ambiguousOrMissingUserTimesAreNeverGuessedFromText() {
        val note = AssistantNarration("narration-ambiguous", "A", "旁白", 77, "继续", 1200)
        assertEquals(-1, narrationAnchorIndex(listOf(history()[0].copy(timestamp = null)), note))
        val unknownBoundary = listOf(history()[0], history()[2].copy(timestamp = null))
        assertEquals(-1, narrationAnchorIndex(unknownBoundary, note))
        val tie = listOf(history()[0], history()[2].copy(timestamp = 1000))
        assertEquals(-1, narrationAnchorIndex(tie, note))
    }
    @Test fun aVerifiedExactIdCanSurviveMissingTimesButCannotOverrideAFutureTime() {
        val note = AssistantNarration("narration-verified", "A", "旁白", 900, "继续", 1200, userTimestamp = 1000)
        assertEquals(0, narrationAnchorIndex(listOf(history()[0].copy(timestamp = null)), note))
        assertEquals(-1, narrationAnchorIndex(listOf(history()[0].copy(timestamp = 2000)), note))
    }
    @Test fun legacyLocalCopiesAreQuarantinedWithoutDroppingServerMessages() {
        val old = org.json.JSONObject("""{"key":"narration-old","session":"A","text":"旧副本","anchor":2,"userText":"继续","timestamp":1200}""")
        val current = org.json.JSONObject("""{"key":"narration-run-5","session":"A","text":"当前旁白","anchor":900,"userText":"继续","timestamp":1200,"userTimestamp":1000,"sequence":5,"positionVersion":1}""")
        val restored = restoreAssistantNarrations(org.json.JSONArray().put(old).put(current))
        assertEquals(listOf("当前旁白"), restored.map { it.text })
        val polluted = history() + HermesMessage("assistant", "旧副本", localKey = "narration-old", timestamp = 1200)
        assertEquals(listOf("继续", "当前旁白", "旧结果", "继续", "新结果"), mergeAssistantNarrations(polluted, restored).map { it.text })
        assertEquals(history(), mergeAssistantNarrations(polluted, emptyList()))
    }
}
