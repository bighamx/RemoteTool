package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class SessionActivityTest {
    @Test fun restoredBookmarksNeedFreshEvidenceAndUnconfirmedSubmissionsStayDistinct() {
        assertNull(sessionActivityLabel(null, 1000, pending = false))
        assertEquals("待核对", sessionActivityLabel(null, 1000, pending = true))
        assertNull(runActivityEvidence(obj("status" to "acceptance_unknown"), 1000).label(1000))
        assertNull(runActivityEvidence(obj("status" to "missing"), 1000).label(1000))
    }

    @Test fun completionImmediatelyOverridesCachedActiveAndOlderPollCannotRestoreIt() {
        val running = sessionActivityEvidence(obj("status" to obj("type" to "active")), 1000)
        var states = mapOf("s" to running)
        states = updateSessionActivity(states, "s", runActivityEvidence(obj("status" to "completed"), 2000))
        assertNull(sessionActivityLabel(states["s"], 2001, false))
        val after = updateSessionActivity(states, "s", sessionActivityEvidence(obj("status" to obj("type" to "active")), 1500))
        assertEquals(states, after)
        assertNull(sessionActivityLabel(after["s"], 3000, false))
    }

    @Test fun genuinelyNewTaskCanRunAfterPreviousCompletion() {
        var states = mapOf("s" to runActivityEvidence(obj("status" to "completed"), 2000))
        states = updateSessionActivity(states, "s", runActivityEvidence(obj("status" to "started"), 3000))
        assertEquals("运行中", sessionActivityLabel(states["s"], 3000, false))
        states = updateSessionActivity(states, "s", sessionActivityEvidence(obj("status" to obj("type" to "idle")), 4000))
        assertNull(sessionActivityLabel(states["s"], 4000, false))
    }

    @Test fun frozenRunningEvidenceExpiresDuringConnectionFailures() {
        val running = runActivityEvidence(obj("status" to "started"), 1000)
        assertEquals("运行中", running.label(31000))
        assertEquals("状态未更新", running.label(31001))
        assertNull(runActivityEvidence(obj("status" to "completed"), 1000).label(100000))
    }

    @Test fun confirmationAndSubmissionAreNotGeneralRunningBadges() {
        val waiting = sessionActivityEvidence(obj("status" to obj("type" to "active", "activeFlags" to JSONArray(listOf("waitingOnUserInput")))), 1000)
        assertEquals("等待确认", waiting.label(1000))
        assertEquals("等待确认", runActivityEvidence(obj("status" to "started", "approval" to obj("request_id" to "r")), 1000).label(1000))
        assertEquals("正在提交", runActivityEvidence(obj("status" to "submitting"), 1000).label(1000))
    }

    @Test fun differentSessionsRemainIndependentAndAllTerminalStatusesClearActivity() {
        val states = mapOf("a" to runActivityEvidence(obj("status" to "started"), 1000), "b" to runActivityEvidence(obj("status" to "started"), 1000))
        for (status in listOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown")) {
            val updated = updateSessionActivity(states, "a", runActivityEvidence(obj("status" to status), 2000))
            assertNull(updated["a"]!!.label(2000))
            assertEquals("运行中", updated["b"]!!.label(2000))
        }
    }

    @Test fun sessionWithoutStatusIsUnknownAndNotEvidenceOfAnIdleTask() {
        assertEquals("unknown", sessionActivityEvidence(obj("title" to "Hermes session"), 1000).state)
        assertEquals("idle", sessionActivityEvidence(obj("status" to obj("type" to "notLoaded")), 1000).state)
    }
}
