package com.chuckiehelper.mobile.phonecontrol

import org.junit.Assert.*
import org.junit.Test

class AgentActivityLedgerTest {
    @Test fun explanationsFollowTheCurrentOperationAndExpireWithoutBecomingNotices() {
        var now = 0L
        val state = AgentActivityLedger { now }
        state.seen("a", "Codex")
        state.toolStarted("a", "正在点击屏幕", "打开网络设置，检查当前连接")
        assertEquals("正在点击屏幕", state.snapshot().operation)
        assertEquals("打开网络设置，检查当前连接", state.snapshot().explanation)
        assertNull(state.snapshot().notice)
        state.toolFinished("a")
        now = 2001
        assertEquals("", state.snapshot().explanation)
        assertFalse(state.snapshot().operating)
        state.toolStarted("a", "读取界面")
        assertEquals("", state.snapshot().explanation)
    }
    @Test fun readOnlyExplanationsAndUiCompletionDoNotDeclareUiOperations() {
        val state = AgentActivityLedger { 0 }
        state.seen("a", "Codex"); state.seen("b", "Hermes")
        state.toolStarted("a", "", "检查手机能力")
        assertFalse(state.snapshot().operating)
        assertEquals("检查手机能力", state.snapshot().explanation)
        state.beginUi("a", "检查界面", 300)
        state.toolStarted("a", "", "界面检查完成")
        state.endUi("a")
        assertFalse(state.snapshot().operating)
        assertEquals("界面检查完成", state.snapshot().explanation)
        state.disconnect("b")
        assertEquals("界面检查完成", state.snapshot().explanation)
        state.disconnect("a")
        assertEquals("", state.snapshot().explanation)
        assertFalse(state.snapshot().connected)
        state.toolFinished("a")
        state.seen("a", "Codex")
        assertEquals("", state.snapshot().explanation)
    }
    @Test fun idleClientsExpireAndAnExpiredDeclarationCannotStayOnScreen() {
        var now = 0L
        val state = AgentActivityLedger { now }
        state.seen("a", "Codex")
        state.beginUi("a", "检查界面", 300)
        now = 121_000
        assertTrue(state.snapshot().connected)
        assertTrue(state.snapshot().operating)
        now = 240_000
        state.touchConnection("a")
        now = 300_000
        assertTrue(state.snapshot().connected)
        assertFalse(state.snapshot().operating)
        now = 540_000
        assertFalse(state.snapshot().connected)
    }
    @Test fun endingOneAgentCannotEndAnotherAgentsDeclaration() {
        val state = AgentActivityLedger { 0 }
        state.seen("a", "Codex"); state.seen("b", "Hermes")
        state.beginUi("a", "正在操作", 300)
        try { state.endUi("b"); fail("foreign UI ownership should be rejected") } catch (error: PhoneToolError) { assertEquals("ui_busy", error.code) }
        assertTrue(state.snapshot().operating)
        assertEquals("Codex", state.snapshot().agent)
        state.endUi("a")
        assertFalse(state.snapshot().operating)
        assertTrue(state.snapshot().connected)
    }
    @Test fun requestingAssistanceNeverFabricatesAReplyAndRepliesAreOwnerScoped() {
        val state = AgentActivityLedger { 0 }
        state.seen("a", "Codex"); state.seen("b", "Hermes")
        val notice = state.notify("a", "请重开主 App", true, 300)
        assertEquals("pending", state.result("a", notice.id)?.status)
        assertNull(state.result("b", notice.id))
        state.reply(notice.id, "done")
        assertEquals("done", state.result("a", notice.id)?.status)
        assertNull(state.snapshot().notice)
        state.reply(notice.id, "dismissed")
        assertEquals("done", state.result("a", notice.id)?.status)
    }
    @Test fun noticesExpireAndDisconnectOnlyRemovesTheCorrespondingClients() {
        var now = 0L
        val state = AgentActivityLedger { now }
        state.seen("web:a", "Codex"); state.seen("direct", "Local")
        val notice = state.notify("web:a", "提示", false, 5)
        state.disconnectWeb()
        assertTrue(state.snapshot().connected)
        assertEquals("Local", state.snapshot().agent)
        assertEquals("cancelled", state.result("web:a", notice.id)?.status)
        val localNotice = state.notify("direct", "本地提示", false, 5)
        now = 6000
        assertEquals("expired", state.result("direct", localNotice.id)?.status)
        assertNull(state.snapshot().notice)
    }
    @Test fun disconnectImmediatelyClearsOnlyTheCallingAgentsActivityAndNotices() {
        val state = AgentActivityLedger { 0 }
        state.seen("a", "Codex"); state.seen("b", "Hermes")
        state.beginUi("a", "操作", 300); state.toolStarted("a", "截图")
        val own = state.notify("a", "协助", true, 300)
        val other = state.notify("b", "其他协助", true, 300)
        val grouped = state.snapshot().connections.associateBy { it.id }
        assertEquals("操作", grouped.getValue("a").goal)
        assertEquals(listOf(own.id), grouped.getValue("a").notices.map { it.id })
        assertEquals(listOf(other.id), grouped.getValue("b").notices.map { it.id })
        state.disconnect("a")
        assertTrue(state.snapshot().connected)
        assertFalse(state.snapshot().operating)
        assertEquals("Hermes", state.snapshot().agent)
        assertEquals("cancelled", state.result("a", own.id)?.status)
        assertEquals("pending", state.result("b", other.id)?.status)
        state.disconnect("b")
        assertFalse(state.snapshot().connected)
        assertNull(state.snapshot().notice)
        state.seen("a", "Codex")
        assertTrue(state.snapshot().connected)
        assertNull(state.snapshot().notice)
    }
    @Test fun stoppingTheServiceCancelsPendingAssistance() {
        val state = AgentActivityLedger { 0 }
        state.seen("a")
        val notice = state.notify("a", "请协助", true, 300)
        state.clear()
        assertEquals("cancelled", state.result("a", notice.id)?.status)
        assertFalse(state.snapshot().connected)
    }
    @Test fun uiOwnershipIsExclusiveAndOnlyRealUiCallsExtendIdleTime() {
        var now = 0L
        val state = AgentActivityLedger { now }
        state.seen("a", "Codex"); state.seen("b", "Hermes")
        state.beginUi("a", "操作", 15)
        assertEquals(2, state.snapshot().connections.size)
        assertEquals("a", state.snapshot().connections.single { it.ownsUi }.id)
        try { state.beginUi("b", "操作", 600); fail("exclusive") } catch (error: PhoneToolError) { assertEquals("ui_busy", error.code) }
        now = 299_999
        state.beginUi("a", "仍在等待", 600)
        now = 300_000
        assertFalse(state.snapshot().connections.any { it.ownsUi })
        state.seen("b", "Hermes")
        state.beginUi("b", "操作", 15)
        now += 299_999
        state.requireUi("b")
        now += 2
        assertTrue(state.snapshot().connections.single { it.id == "b" }.ownsUi)
        state.disconnect("b")
        state.seen("a", "Codex")
        state.beginUi("a", "接管", 600)
        assertEquals("a", state.snapshot().connections.single { it.ownsUi }.id)
    }
    @Test fun fiveMinuteIdleClearsDisplayAndHelpWithoutClosingOtherActiveOwner() {
        var now = 0L
        val state = AgentActivityLedger { now }
        state.seen("idle", "Idle"); state.beginUi("idle", "等待", 300)
        val help = state.notify("idle", "help", true, 600)
        now = 299_999
        state.seen("active", "Active")
        assertEquals(2, state.snapshot().connections.size)
        now = 300_000
        assertEquals(listOf("active"), state.snapshot().connections.map { it.id })
        assertEquals("cancelled", state.result("idle", help.id)?.status)
    }
}
