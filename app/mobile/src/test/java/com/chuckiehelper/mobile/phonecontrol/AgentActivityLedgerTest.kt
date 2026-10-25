package com.chuckiehelper.mobile.phonecontrol

import org.junit.Assert.*
import org.junit.Test

class AgentActivityLedgerTest {
    @Test fun idleClientsExpireAndAnExpiredDeclarationCannotStayOnScreen() {
        var now = 0L
        val state = AgentActivityLedger { now }
        state.seen("a", "Codex")
        state.beginUi("a", "检查界面", 300)
        now = 121_000
        assertTrue(state.snapshot().connected)
        assertTrue(state.snapshot().operating)
        now = 301_000
        assertFalse(state.snapshot().connected)
        assertFalse(state.snapshot().operating)
    }
    @Test fun endingOneAgentCannotEndAnotherAgentsDeclaration() {
        val state = AgentActivityLedger { 0 }
        state.seen("a", "Codex"); state.seen("b", "Hermes")
        state.beginUi("a", "正在操作", 300)
        state.endUi("b")
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
}
