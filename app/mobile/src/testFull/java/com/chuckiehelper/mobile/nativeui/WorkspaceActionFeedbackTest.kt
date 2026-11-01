package com.chuckiehelper.mobile.nativeui
import org.junit.Assert.*
import org.junit.Test
class WorkspaceActionFeedbackTest {
    @Test fun feedbackSurvivesReadRefreshAndLateOperationsCannotClobberNewState(){
        val state=WorkspaceActionFeedback()
        val first=state.begin("a")!!
        assertNull(state.begin("a"))
        state.fail(first,"工作空间登录已失效，请重新登录")
        state.finish(first)
        assertEquals("工作空间登录已失效，请重新登录",state.errors["a"])
        assertTrue(state.busy.isEmpty())
        val second=state.begin("a")!!
        state.fail(first,"过期回包")
        state.finish(first)
        assertTrue("a" in state.busy)
        assertNull(state.errors["a"])
        state.succeed(second,"已重置");state.finish(second)
        assertEquals("已重置",state.notices["a"])
        val other=state.begin("b")!!;state.clear();state.fail(other,"旧连接错误");state.finish(other)
        assertTrue(state.errors.isEmpty());assertTrue(state.busy.isEmpty())
    }
}
