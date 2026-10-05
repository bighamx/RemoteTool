package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class PowerPolicyTest {
    @Test fun observationStartsOnlyOnVisibilityTransitions() {
        var starts = 0; var stops = 0
        val gate = ForegroundObservation({ starts++ }, { stops++ })
        assertFalse(gate.active)
        gate.setActive(false)
        assertEquals(0, starts); assertEquals(0, stops)
        gate.setActive(true); gate.setActive(true)
        assertEquals(1, starts)
        gate.setActive(false); gate.setActive(false)
        assertEquals(1, stops)
        gate.setActive(true)
        assertEquals(2, starts)
    }
    @Test fun idlePagesUseLessFrequentReadsButActiveProgressRemainsResponsive() {
        assertEquals(2_000, agentExternalPollDelay(true))
        assertTrue(agentExternalPollDelay(false) >= 10_000)
        assertTrue(agentContextPollDelay(false) > agentContextPollDelay(true))
        assertTrue(agentSessionPollDelay(false) > agentSessionPollDelay(true))
        assertTrue(agentUsagePollDelay(false) > agentUsagePollDelay(true))
    }
    @Test fun streamReplacesFrequentStatusReadsAndOfflineBackoffIsBounded() {
        assertEquals(5_000, agentStatusPollDelay(true, 0))
        assertEquals(2_000, agentStatusPollDelay(false, 0))
        assertTrue(agentStatusPollDelay(false, 2) > agentStatusPollDelay(false, 1))
        assertEquals(30_000, agentStatusPollDelay(false, 100))
    }
    @Test fun pausedPlaybackIsNotStartedOnForegroundReturn() {
        val state = PlaybackVisibility()
        assertFalse(state.show())
        assertFalse(state.hide(false))
        assertFalse(state.show())
    }
    @Test fun bufferingOrPlayingIntentResumesOnceAndSurvivesRepeatedStopEvents() {
        val state = PlaybackVisibility()
        assertTrue(state.hide(true))
        state.hide(false)
        assertTrue(state.show())
        assertFalse(state.show())
    }
}
