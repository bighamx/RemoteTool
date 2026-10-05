package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionPageTest {
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
