package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class ToolEventIdentityTest {
    @Test fun parallelSameNameToolsCompleteByNativeItemId() {
        val started = listOf(HermesEvent("执行中", "终端", "A", "item-a"), HermesEvent("执行中", "终端", "B", "item-b"))
        val first = completeToolEvent(started, "终端", "item-a", true)
        assertEquals(listOf("失败", "执行中"), first.map { it.type })
        val second = completeToolEvent(first, "终端", "item-b", false)
        assertEquals(listOf("失败", "完成"), second.map { it.type })
        assertEquals(started, completeToolEvent(started, "终端", "unknown-item", false))
    }
    @Test fun legacyCompletionNeverGuessesBetweenParallelSameNameTools() {
        val rows = listOf(HermesEvent("执行中", "终端"), HermesEvent("执行中", "终端"))
        val ambiguous = completeToolEvent(rows, "终端", null, false)
        assertEquals(listOf("状态待核对", "状态待核对", "进度"), ambiguous.map { it.type })
        assertEquals(rows, ambiguous.take(2).map { it.copy(type = "执行中") })
        assertEquals("完成", completeToolEvent(rows.take(1), "终端", null, false).single().type)
    }
    @Test fun ambiguousCompletionIsLabeledAsPendingReview() {
        assertEquals("待核对", toolProgressStatusLabel("状态待核对"))
        assertEquals("待核对", toolProgressStatusLabel("unknown"))
        assertEquals("", toolProgressStatusLabel("进度"))
    }
}
