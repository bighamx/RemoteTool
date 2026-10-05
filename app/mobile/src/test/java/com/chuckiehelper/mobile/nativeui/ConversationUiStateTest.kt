package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class ConversationUiStateTest {
    @Test fun lateFailureAndDraftRestorationStayInTheirOriginatingChat() {
        var state = ConversationUiState().draft("A", "允许").submitting("A", true)
        state = state.draft("B", "另一个问题")
        state = state.error("A", "连接中断").submitting("A", false)
        assertEquals("另一个问题", state.entry("B").draft)
        assertNull(state.entry("B").error)
        assertEquals("允许", state.entry("A").draft)
        assertEquals("连接中断", state.entry("A").error)
    }
    @Test fun concurrentSubmissionAndCompletionCannotUnlockAnotherChat() {
        var state = ConversationUiState().submitting("A", true).submitting("B", true)
        state = state.submitting("A", false).error("A", null)
        assertFalse(state.entry("A").submitting)
        assertTrue(state.entry("B").submitting)
        assertEquals(ConversationUiEntry(), state.entry("C"))
    }
    @Test fun deletingOneChatKeepsOtherDrafts() {
        val state = ConversationUiState().draft("A", "a").draft("B", "b").forget("A")
        assertEquals("", state.entry("A").draft)
        assertEquals("b", state.entry("B").draft)
    }
}
