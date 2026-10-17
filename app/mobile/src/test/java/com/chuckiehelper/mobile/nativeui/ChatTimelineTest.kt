package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class ChatTimelineTest {
    @Test fun bottomIndexedListKeepsTheOriginalVisualOrder() {
        val messages = chatMessageItems(listOf(HermesMessage("user", "question", 1), HermesMessage("assistant", "answer", 2)))
        val visual = chatTimelineRows(messages, true, true).asReversed()
        assertEquals(listOf("history-start", messages[0].key, messages[1].key, "execution", "approval", "conversation-bottom"), visual.map { it.key })
    }

    @Test fun olderHistoryDoesNotMoveExistingRowIndices() {
        val messages = chatMessageItems(listOf(HermesMessage("user", "question", 2), HermesMessage("assistant", "answer", 3)))
        val before = chatTimelineRows(messages, true, false)
        val after = chatTimelineRows(chatMessageItems(listOf(HermesMessage("assistant", "older", 1)) + messages.map { it.message }), true, false)
        before.filter { it.kind != ChatRowKind.History }.forEach { row ->
            assertEquals(before.indexOf(row), after.indexOfFirst { it.key == row.key })
        }
    }

    @Test fun emptyAndLoadingListsHaveStableBoundaryKeys() {
        val empty = chatTimelineRows(emptyList(), false, false, true)
        assertEquals(listOf("conversation-bottom", "conversation-empty", "history-start"), empty.map { it.key })
        assertEquals(empty.size, empty.map { it.key }.distinct().size)
    }
}
