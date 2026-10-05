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
        val user = HermesMessage("user", "部署 office", serverId = 1)
        val final = HermesMessage("assistant", "部署完成", serverId = 2)
        val note = AssistantNarration("narration-run-1", "A", "先摸清部署形态", 1, user.text, 123)
        val merged = mergeAssistantNarrations(listOf(user, final), listOf(note))
        assertEquals(listOf(user.text, note.text, final.text), merged.map { it.text })
        assertEquals(merged, mergeAssistantNarrations(merged, listOf(note)))
        assertEquals(123L, merged[1].timestamp)
        val canonical = final.copy(text = note.text + "\n\n部署完成")
        assertEquals(listOf(user, canonical), mergeAssistantNarrations(listOf(user, canonical), listOf(note)))
    }
    @Test fun aNewInstructionCannotReceiveNarrationFromThePreviousInstruction() {
        val first = HermesMessage("user", "部署 office", serverId = 1)
        val second = HermesMessage("user", "检查 home", serverId = 3)
        val final = HermesMessage("assistant", "检查完毕", serverId = 4)
        val note = AssistantNarration("narration-run-1", "A", "先摸清部署形态", 1, first.text, 123)
        assertEquals(listOf(first.text, note.text, second.text, final.text), mergeAssistantNarrations(listOf(first, second, final), listOf(note)).map { it.text })
    }
    @Test fun thirtyCharactersAreKeptAndTheRestOfThatRunIsReplacedByOneEllipsis() {
        assertEquals("a".repeat(30), truncateNarration("a".repeat(30)))
        assertEquals("a".repeat(30) + "…", truncateNarration("a".repeat(31)))
        assertEquals("a".repeat(30) + "…", truncateNarration("a".repeat(3000)))
    }
    @Test fun ChineseRestartsTheLimitForEachIndependentRun() {
        assertEquals("前" + "x".repeat(30) + "…中" + "y".repeat(30) + "…后",
            truncateNarration("前" + "x".repeat(50) + "中" + "y".repeat(80) + "后"))
        assertEquals("中文说明无需截断", truncateNarration("中文说明无需截断"))
    }
    @Test fun PathsSpacesAndLineBreaksBelongToTheSameNonChineseRun() {
        val code = "C:\\Users\\user\\AppData\\Local\\OpenAI\\Codex\\bin\npython import subprocess"
        assertEquals("路径" + code.take(30) + "…接着说明", truncateNarration("路径${code}接着说明"))
    }
    @Test fun EmojiAndSupplementaryHanAreCountedWithoutSplittingSurrogates() {
        val emoji = "\uD83D\uDE42"
        val han = "\uD840\uDC00"
        assertEquals(emoji.repeat(30) + "…" + han + emoji.repeat(30) + "…",
            truncateNarration(emoji.repeat(40) + han + emoji.repeat(40)))
    }
    @Test fun KnownNarrationIsShortenedWithoutEditingTheFinalReplyOrTheRawText() {
        val note = "先检查" + "x".repeat(40) + "再继续"
        val final = note + "\n\n完整结果\n" + "y".repeat(80)
        assertEquals("先检查" + "x".repeat(30) + "…再继续\n\n完整结果\n" + "y".repeat(80),
            displayNarration(final, false, listOf(note)))
        assertEquals(final, displayNarration(final, false))
        assertEquals(40, note.count { it == 'x' })
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
}
