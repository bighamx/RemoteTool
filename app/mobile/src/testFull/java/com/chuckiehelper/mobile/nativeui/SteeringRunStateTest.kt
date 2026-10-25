package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class SteeringRunStateTest {
    @Test fun hermesGetRunningCanSteerWithoutLosingRunTrackingAndCodexKeepsItsVocabulary() {
        assertNull(steeringRunPreflightFailure("hermes", "running"))
        assertNull(steeringRunPreflightFailure("codex", "started"))
        val unknownCodexState = steeringRunPreflightFailure("codex", "running")!!
        assertTrue(writeWasRejected(unknownCodexState))
        assertFalse(staleRunFailure(unknownCodexState))
    }

    @Test fun temporaryHermesRejectionRestoresDraftButDoesNotDropTheLiveTask() {
        for (status in listOf("started", "submitting", "queued", "waiting_for_approval", "stopping", "")) {
            val failure = steeringRunPreflightFailure("hermes", status)!!
            assertTrue("$status must preserve the draft", writeWasRejected(failure))
            assertFalse("$status must keep the current task attached", staleRunFailure(failure))
        }
    }

    @Test fun confirmedTerminalStatesStillUseTheExistingDraftRecoveryAndStaleRunCleanup() {
        for (agent in listOf("hermes", "codex"))
            for (status in listOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown")) {
                val failure = steeringRunPreflightFailure(agent, status)!!
                assertTrue(writeWasRejected(failure))
                assertTrue(staleRunFailure(failure))
            }
    }
}
