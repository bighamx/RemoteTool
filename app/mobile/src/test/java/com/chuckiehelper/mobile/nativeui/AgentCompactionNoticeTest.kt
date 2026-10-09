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
        assertEquals(listOf(row(2, time + 1000)), mergeCompactionNotices(listOf(row(2, time + 1000)), listOf(note)))
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

    @Test fun datedHistoryRejectsContradictoryCachedBoundary() {
        val history = listOf(row(90, time + 60000), row(2, time + 61000))
        val merged = mergeCompactionNotices(history, listOf(AgentCompactionNotice("a", "s", time, 90)))
        assertEquals(history, merged)
    }

    @Test fun oldCompletionsNeverClusterAfterRecentUndatedContinue() {
        val history = listOf(row(12, null), row(90, null), row(2, time + 1000))
        val old = (1..7).map { AgentCompactionNotice("old-$it", "s", time - it * 86_400_000L, 90) }
        assertEquals(history, mergeCompactionNotices(history, old))
        assertEquals(history, mergeCompactionNotices(mergeCompactionNotices(history, old), old))
        assertTrue(mergeCompactionNotices(emptyList(), old).isEmpty())
    }

    @Test fun staleDatedAnchorCannotOverrideChronologicalSlot() {
        val history = listOf(row(12, time - 1000), row(90, time + 1000), row(2, time + 2000))
        val merged = mergeCompactionNotices(history, listOf(AgentCompactionNotice("a", "s", time, 90)))
        assertEquals(listOf(12L, 0L, 90L, 2L), merged.map { it.serverId })
    }

    @Test fun taskAndRolloutWithSameNativeIdentityProduceOneStableNoticeInEitherArrivalOrder() {
        val task = AgentCompactionNotice("task", "s", time + 30_000, 90, "native-a", time - 60_000)
        val native = AgentCompactionNotice("external-native-a", "s", time, 90, "native-a")
        for (input in listOf(listOf(task, native), listOf(native, task))) {
            val merged = mergeCompactionNotices(listOf(row(90, time - 60_000)), input)
            assertEquals(1, merged.count { it.role == "system" })
            assertEquals(merged, mergeCompactionNotices(merged, input))
            val normalized = coalesceCompactionNotices(input)
            assertEquals(1, normalized.size)
            assertEquals("native-a", normalized.single().compactionId)
            assertEquals(time, normalized.single().timestamp)
            assertEquals(normalized, coalesceCompactionNotices(normalized))
        }
    }
    @Test fun delayedTaskCheckReconcilesNativeCompletionInsideItsObservedRunWindow() {
        val task = AgentCompactionNotice("task", "s", time + 120_000, 90, startedAt = time - 60_000)
        val native = AgentCompactionNotice("external-native-a", "s", time, 90, "native-a")
        val rows = coalesceCompactionNotices(listOf(task, native))
        assertEquals(1, rows.size)
        assertEquals(time, rows.single().timestamp)
        assertEquals("native-a", rows.single().compactionId)
        assertEquals(task.run, rows.single().run)
    }
    @Test fun twoNativeOperationsStaySeparateEvenWithinFiveSeconds() {
        val a = AgentCompactionNotice("external-a", "s", time, 90, "a")
        val b = AgentCompactionNotice("external-b", "s", time + 1000, 90, "b")
        assertEquals(2, coalesceCompactionNotices(listOf(a, b)).size)
        assertEquals(2, mergeCompactionNotices(listOf(row(90, time - 1000)), listOf(a, b)).count { it.role == "system" })
        val appA = a.copy(run = "task-a")
        assertEquals(2, coalesceCompactionNotices(listOf(appA, b)).size)
    }
    @Test fun oldCachesAreReconciledWithoutMergingDifferentSessionsOrDistinctTasks() {
        val task = AgentCompactionNotice("task", "s", time, 90)
        val legacy = AgentCompactionNotice("external-native-a", "s", time + 1000, 90)
        val native = AgentCompactionNotice.restore(legacy.json())!!
        assertEquals("native-a", native.compactionId)
        assertEquals(1, coalesceCompactionNotices(listOf(task, native)).size)
        assertEquals(2, coalesceCompactionNotices(listOf(task, native.copy(session = "other"))).size)
        assertEquals(2, coalesceCompactionNotices(listOf(task, task.copy(run = "another-task"))).size)
        val outside = native.copy(timestamp = time - 120_000)
        assertEquals(2, coalesceCompactionNotices(listOf(task.copy(startedAt = time - 60_000), outside)).size)
    }
}
