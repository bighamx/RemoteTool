package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolNarrationRecoveryTest {
    private fun history(command: Any, name: String = "terminal", content: String = "", id: Long = 21) =
        obj("id" to id, "role" to "assistant", "content" to content, "timestamp" to 110,
            "tool_calls" to JSONArray().put(obj("id" to "call", "function" to obj("name" to name,
                "arguments" to JSONObject().put("command", command).toString()))))

    @Test fun quotedConsoleSectionLabelsAreVisibleWithoutStandaloneAssistantText() {
        assertEquals("1. 全仓搜索（源码+历史）:", terminalNarration("terminal", "echo \"=== 1. 全仓搜索（源码+历史）:\"; git status"))
        assertEquals("确认历史内容", terminalNarration("terminal", "cd /d/GIT/ChuckieHelper && echo '=== 确认历史内容 ==='; git status"))
        assertEquals("检查当前任务", terminalNarration("powershell", "Write-Host \"=== 检查当前任务 ===\"; Get-Process"))
    }
    @Test fun arbitraryEchoCommandsAndCodeAreNotNarration() {
        assertNull(terminalNarration("terminal", "echo \"$" + "TOKEN\""))
        assertNull(terminalNarration("terminal", "echo \"普通程序输出\""))
        assertNull(terminalNarration("terminal", "git status; echo \"=== 后续程序输出\""))
        assertNull(terminalNarration("terminal", "echo \"=== 1234\""))
        assertNull(terminalNarration("file_write", "# 这是文件内容"))
    }
    @Test fun restoreOriginalCommentEvenIfSsePreviewWasEmptyOrCutOff() {
        val row = history("# 先检查当前部署，再核对运行状态\nssh office hostname")
        val projected = agentHistoryMessage("hermes", row)!!
        assertEquals("先检查当前部署，再核对运行状态", projected.text)
        assertEquals(21L, projected.serverId)
        assertTrue(projected.narration)
        assertNull(agentHistoryMessage("codex", row))
    }
    @Test fun historyRefreshAndSseReconcileIntoOneStableBubble() {
        val user = HermesMessage("user", "检查项目", serverId = 20, timestamp = 100_000)
        val row = history("echo \"=== 确认历史内容 ===\"; git status")
        val native = agentHistoryMessage("hermes", row)!!
        val note = assistantNarrationEvent("narration-run-5", "session", native.text, 110_000, listOf(user))!!.copy(run = "run")
        val live = mergeAssistantNarrations(listOf(user), listOf(note))
        val refreshed = mergeAssistantNarrations(listOf(user, native), listOf(note))
        assertEquals(live.map { it.text }, refreshed.map { it.text })
        assertEquals(live.last().localKey, refreshed.last().localKey)
        assertEquals(refreshed, mergeAssistantNarrations(refreshed, listOf(note)))
    }
    @Test fun preservesRealAssistantTextAndIgnoresInvalidArguments() {
        val row = history("echo \"=== 查运行状态 ===\"", content = "我会先核对机器运行状态")
        assertEquals("我会先核对机器运行状态", agentHistoryMessage("hermes", row)!!.text)
        row.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").put("arguments", "{broken")
        row.put("content", "")
        assertNull(agentHistoryMessage("hermes", row))
    }
    @Test fun handlesStringEncodedCallsAndCommandBatchesWithoutDuplicateHeadings() {
        val row = history(JSONArray().put("# 检查连接\nhostname").put("# 检查连接\nwhoami").put("echo \"=== 核对进程 ===\""))
        row.put("tool_calls", row.getJSONArray("tool_calls").toString())
        assertEquals("检查连接\n核对进程", historyToolNarration(row))
    }
}
