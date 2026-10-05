package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class AgentCompactionNoticeTest {
    private val time = 1_791_200_000_000L
    private fun row(id: Long, timestamp: Long? = time) = HermesMessage("user", "继续", serverId = id, timestamp = timestamp)

    @Test fun onlySuccessfulCompactionsProduceNotices() {
        assertTrue(successfulCompaction("c1", obj("status" to "completed", "kind" to "compact")))
        assertTrue(successfulCompaction("hcompact_1", obj("status" to "completed")))
        listOf("started", "failed", "cancelled", "acceptance_unknown").forEach {
            assertFalse(successfulCompaction("hcompact_1", obj("status" to it, "kind" to "compact")))
        }
        assertFalse(successfulCompaction("c1", obj("status" to "completed")))
    }

    @Test fun completionSurvivesRestartHistoryReloadAndDuplicateChecks() {
        val note = AgentCompactionNotice("run1", "session1", time + 1000, 90)
        val restored = AgentCompactionNotice.restore(note.json())!!
        assertEquals(note, restored)
        val history = listOf(row(90), row(2, time + 2000))
        val merged = mergeCompactionNotices(history, listOf(restored, restored))
        assertEquals(listOf("user", "system", "user"), merged.map { it.role })
        assertEquals("上下文压缩完成", merged[1].text)
        assertEquals(merged, mergeCompactionNotices(merged, listOf(restored)))
        assertEquals(history, merged.filterNot { it.role == "system" })
    }

    @Test fun unknownNativeTimesUseExactBoundaryNotNumericIdOrder() {
        val merged = mergeCompactionNotices(listOf(row(90, null), row(2, null)), listOf(AgentCompactionNotice("a", "s", time, 90)))
        assertEquals(2L, merged.last().serverId)
        assertEquals("system", merged[1].role)
        assertEquals(listOf(row(90, null)), mergeCompactionNotices(listOf(row(90, null)), listOf(AgentCompactionNotice("a", "s", time, 999))))
    }

    @Test fun oldNoticesStayAheadOfNewMessagesAndOutsideWindowNeverAppendToTail() {
        val note = AgentCompactionNotice("a", "s", time, 999)
        assertEquals("system", mergeCompactionNotices(listOf(row(2, time + 1000)), listOf(note)).first().role)
        val fullWindow = (1L..500).map { row(it, time - 1000) }
        assertEquals(fullWindow, mergeCompactionNotices(fullWindow, listOf(note)))
    }

    @Test fun multipleCompactionsAtOneBoundaryRemainChronological() {
        val a = AgentCompactionNotice("a", "s", time + 1000, 90)
        val b = AgentCompactionNotice("b", "s", time + 2000, 90)
        assertEquals(listOf("compaction-a", "compaction-b"),
            mergeCompactionNotices(listOf(row(90, null)), listOf(b, a)).filter { it.role == "system" }.map { it.localKey })
        assertNull(AgentCompactionNotice.restore(obj("run" to "a", "session" to "s")))
    }

    @Test fun serverClockAheadDoesNotPlaceCompletionBeforeItsKnownBoundary() {
        val history = listOf(row(90, time + 60000), row(2, time + 61000))
        val merged = mergeCompactionNotices(history, listOf(AgentCompactionNotice("a", "s", time, 90)))
        assertEquals(listOf("user", "system", "user"), merged.map { it.role })
    }
}
