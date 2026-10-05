package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class AgentRunTimingTest {
    private val start = 1_791_200_000_000L

    @Test fun textToolsAndApprovalResetResponseTimeButNotTaskStart() {
        var timing = AgentRunTiming(start)
        listOf("message.delta", "tool.started", "tool.completed", "message.interim", "approval.request").forEachIndexed { i, kind ->
            val now = start + (i + 1) * 1000
            timing = timing.event(obj("seq" to i, "event" to kind), now)
            assertEquals(now, timing.lastResponseAt)
            assertEquals(start, timing.startedAt)
        }
    }

    @Test fun streamReconnectAndNonModelEventsDoNotResetResponseTime() {
        val first = AgentRunTiming(start).event(obj("seq" to 5, "event" to "tool.started"), start + 1000)
        val restored = AgentRunTiming.restore(first.json())
        assertEquals(first, restored.event(obj("seq" to 5, "event" to "tool.started"), start + 60000))
        assertEquals(first, restored.event(obj("seq" to 4, "event" to "message.delta"), start + 60000))
        assertEquals(first.lastResponseAt, restored.event(obj("seq" to 6, "event" to "run.started"), start + 60000).lastResponseAt)
    }

    @Test fun newResponsesUsePhoneReceiptTimeRatherThanServerTime() {
        val timing = AgentRunTiming(start).event(obj("seq" to 1, "event" to "message.delta", "timestamp" to start + 1000), start + 60000)
        assertEquals(start + 60000, timing.lastResponseAt)
        assertEquals(start + 60000, timing.event(obj("seq" to 1, "event" to "message.delta", "timestamp" to start + 1000), start + 120000).lastResponseAt)
        assertEquals(start + 120000, timing.event(obj("seq" to 2, "event" to "tool.started", "timestamp" to start), start + 120000).lastResponseAt)
    }

    @Test fun differentTasksKeepIndependentClocksAndUnknownStartIsHonest() {
        val a = AgentRunTiming(start).event(obj("seq" to 1, "event" to "tool.started"), start + 3000)
        val b = AgentRunTiming(start + 5000)
        assertEquals(start + 3000, a.lastResponseAt)
        assertNull(b.lastResponseAt)
        assertEquals("—", formatRunElapsed(null, start))
        assertEquals("00:00", formatRunElapsed(start + 1000, start))
        assertEquals("02:03", formatRunElapsed(start, start + 123000))
        assertEquals("1:02:03", formatRunElapsed(start, start + 3723000))
    }
}
