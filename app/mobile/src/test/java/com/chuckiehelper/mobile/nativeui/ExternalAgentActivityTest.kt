package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class ExternalAgentActivityTest {
    private fun active() = obj("available" to true, "running" to true, "session_id" to "cli-session",
        "started_at" to 1_791_200_000_000L, "last_response_at" to 1_791_200_002_000L)

    @Test fun onlyMatchingFreshVerifiedExternalTaskIsShown() {
        assertTrue(externalActivityRunning(active(), "cli-session", 1000, 1000))
        assertFalse(externalActivityRunning(active(), "different-session", 1000, 1000))
        assertFalse(externalActivityRunning(active(), "cli-session", 31001, 1000))
        assertFalse(externalActivityRunning(active().put("running", false), "cli-session", 1000, 1000))
        assertFalse(externalActivityRunning(active().put("available", false), "cli-session", 1000, 1000))
    }

    @Test fun timersUseOriginalTurnAndReplyTimesRatherThanAppOpenTime() {
        val timing = externalActivityTiming(active())
        assertEquals(1_791_200_000_000L, timing.startedAt)
        assertEquals(1_791_200_002_000L, timing.lastResponseAt)
        assertNull(externalActivityTiming(active().put("last_response_at", org.json.JSONObject.NULL)).lastResponseAt)
    }

    @Test fun toolStateAndBoundedPreviewsArePresentedWithoutRawOutputs() {
        val snapshot = active().put("progress", JSONArray(listOf(
            obj("tool" to "terminal", "preview" to "echo hello", "status" to "running"),
            obj("tool" to "terminal", "preview" to "second", "status" to "completed"),
            obj("tool" to "read_file", "preview" to "test.txt", "status" to "failed"),
        ))).put("activity_text", "等待模型响应")
        val events = externalActivityEvents(snapshot)
        assertEquals(listOf("执行中", "完成", "失败", "进度"), events.map { it.type })
        assertEquals("echo hello", events.first().detail)
        assertEquals(4, events.size)
        assertTrue(externalActivityEvents(null).isEmpty())
    }
}
