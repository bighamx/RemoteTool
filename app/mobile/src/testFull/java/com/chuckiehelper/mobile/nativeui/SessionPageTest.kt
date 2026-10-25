package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class SessionPageTest {
    @Test fun nativePinAcknowledgementAcceptsBothAgentsAndRejectsMissingOrWrongState() {
        assertTrue(confirmedSessionPin(JSONObject("{\"pinned\":true}"), true))
        assertTrue(confirmedSessionPin(JSONObject("{\"session\":{\"pinned\":false}}"), false))
        assertFalse(confirmedSessionPin(JSONObject("{}"), false))
        assertFalse(confirmedSessionPin(JSONObject("{\"pinned\":true}"), false))
    }
    @Test fun pinsPrecedeRecentRowsWithoutChangingOrderWithinGroups() {
        val pins = setOf("old", "older")
        assertEquals(listOf("old", "older", "new", "recent"),
            pinnedSessionsFirst(listOf("new", "old", "recent", "older")) { it in pins })
    }

    @Test fun backfilledPinsRemainUniqueAcrossPagesAndUnpinRestoresServerOrder() {
        val rows = mergeSessionPage(listOf("new", "old"), listOf("old", "older")) { it }
        assertEquals(listOf("older", "new", "old"), pinnedSessionsFirst(rows) { it == "older" })
        assertEquals(rows, pinnedSessionsFirst(rows) { false })
    }
    private data class Session(val id: String, val status: String = "idle")

    @Test fun initialPageKeepsOnlyOneRowForEachSession() {
        val newest = Session("a", "active")
        val rows = mergeSessionPage(emptyList(), listOf(newest, Session("a"), Session("b"), Session("a"))) { it.id }
        assertEquals(listOf(newest, Session("b")), rows)
    }

    @Test fun overlappingPageUpdatesStatusWithoutDuplicatingOrMovingExistingRows() {
        val rows = mergeSessionPage(
            listOf(Session("a"), Session("b")),
            listOf(Session("b", "active"), Session("b"), Session("c")),
        ) { it.id }
        assertEquals(listOf(Session("a"), Session("b", "active"), Session("c")), rows)
    }

    @Test fun malformedIdsAndDuplicateCachedRowsCannotBecomeLazyListKeys() {
        val rows = mergeSessionPage(
            listOf(Session("a"), Session("a"), Session("")),
            listOf(Session(" "), Session("null"), Session("b")),
        ) { it.id }
        assertEquals(listOf(Session("a"), Session("b")), rows)
    }
}
