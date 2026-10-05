package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class AssistantNarrationTest {
    @Test fun explanatoryCommentIsSeparatedFromTheCommand() {
        assertEquals("先摸清 office 上的 ChuckieHelper 部署形态", terminalNarration("terminal", "# 先摸清 office 上的 ChuckieHelper 部署形态\nssh office \"dir /b D:\\GIT\""))
        assertEquals("先摸清 office 上的 ChuckieHelper 部署形态", terminalNarration("terminal", "# 先摸清 office 上的 ChuckieHelper 部署形态： ssh office \"dir /b D:\\GIT\""))
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
}
