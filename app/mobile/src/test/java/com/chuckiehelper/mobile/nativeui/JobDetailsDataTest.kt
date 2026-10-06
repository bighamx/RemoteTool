package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class JobDetailsDataTest {
    private fun page(attempt: String, start: Int, vararg indices: Int) = obj("attempt" to attempt, "nextOffset" to (indices.maxOrNull()?.plus(1) ?: start),
        "total" to 20, "data" to JSONArray(indices.map { obj("index" to it, "text" to "日志 $it") }))
    @Test fun duplicatePagesDoNotDuplicateOrReorderLines() {
        val initial = JobConsoleBuffer().merge(page("a", 0, 4, 5))
        val merged = initial.merge(page("a", 0, 5, 6))
        assertEquals(listOf(4,5,6), merged.lines.map { it.optInt("index") })
        val replayed = merged.merge(page("a", 0, 5, 6))
        assertEquals(merged.nextOffset,replayed.nextOffset)
        assertEquals(merged.lines.map { it.toString() },replayed.lines.map { it.toString() })
    }
    @Test fun changingAttemptsOrResettingCursorDropsThePreviousExecution() {
        val initial = JobConsoleBuffer().merge(page("a", 0, 0, 1))
        assertEquals(listOf(0), initial.merge(page("b",0,0)).lines.map { it.optInt("index") })
        assertEquals(listOf(8), initial.merge(page("a",0,8).put("reset",true)).lines.map { it.optInt("index") })
    }
    @Test fun loadingOlderLinesPreservesTheLivePollingCursor() {
        val initial = JobConsoleBuffer().merge(page("a",0,10,11))
        val older = initial.merge(page("a",0,8,9),older=true)
        assertEquals(12,older.nextOffset)
        assertEquals(listOf(8,9,10,11),older.lines.map { it.optInt("index") })
    }
    @Test fun largeStreamsHaveABoundedMemoryWindow() {
        val buffer = JobConsoleBuffer().merge(page("a",0,*(0..2500).toList().toIntArray()))
        assertEquals(2000,buffer.lines.size)
        assertEquals(501,buffer.lines.first().optInt("index"))
    }
    @Test fun objectArgumentsBecomeFieldsAndEmptyValuesRemainReadable() {
        assertEquals(listOf("param.TargetPath" to "D:/media", "param.enabled" to "true"),
            jobParameterFields(obj("TargetPath" to "D:/media","enabled" to true),"param"))
        assertEquals(listOf("path" to "未设置"),jobParameterFields(null,"path"))
        assertTrue(jobActive("Processing"));assertTrue(jobActive("Scheduled"));assertFalse(jobActive("Succeeded"))
        assertEquals("已完成",jobStateLabel("Succeeded"))
    }
    @Test fun deletingAnAlreadyFinishedJobDoesNotExtendItsExecutionTime() {
        val job = obj("history" to JSONArray(listOf(
            obj("stateName" to "Processing", "createdAt" to "2026-10-06T01:00:00Z"),
            obj("stateName" to "Succeeded", "createdAt" to "2026-10-06T01:01:00Z"),
            obj("stateName" to "Deleted", "createdAt" to "2026-10-06T02:00:00Z"))))
        assertEquals(parseMessageTimestamp("2026-10-06T01:01:00Z"),jobExecutionFinishedAt(job))
    }
}
