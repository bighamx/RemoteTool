package com.chuckiehelper.mobile.nativeui

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentRecoveryTest {
    @Test fun transientFailureRecoversWithBackoff() = runBlocking {
        var calls = 0
        val delays = mutableListOf<Long>()
        val value = retryAgentRead(pause = { delays.add(it) }) {
            if (++calls < 3) throw IOException("offline")
            "history"
        }
        assertEquals("history", value)
        assertEquals(listOf(1000L, 2000L), delays)
        assertEquals(3, calls)
    }

    @Test fun persistentFailureStopsAfterFourReads() = runBlocking {
        var calls = 0
        val delays = mutableListOf<Long>()
        val failure = IOException("offline")
        try {
            retryAgentRead(pause = { delays.add(it) }) { calls++; throw failure }
            fail("Must report exhausted retries")
        } catch (error: IOException) { assertSame(failure, error) }
        assertEquals(4, calls)
        assertEquals(listOf(1000L, 2000L, 4000L), delays)
    }

    @Test fun permissionsAndCancellationNeverRetry() = runBlocking {
        for (failure in listOf(LoginRequired(), ApiRequestFailure("forbidden", 403), CancellationException("switched"))) {
            var calls = 0
            try {
                retryAgentRead(pause = { fail("Must not retry") }) { calls++; throw failure }
                fail("Must preserve failure")
            } catch (error: Exception) { assertSame(failure, error) }
            assertEquals(1, calls)
        }
    }

    @Test fun perReadTimeoutCanRecoverButParentCancellationStops() = runBlocking {
        var calls = 0
        val value = retryAgentRead(pause = {}) {
            if (++calls == 1) withTimeout(1) { awaitCancellation() }
            "ready"
        }
        assertEquals("ready", value)
        assertEquals(2, calls)
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            retryAgentRead(pause = { awaitCancellation() }) { throw IOException("offline") }
        }
        worker.cancelAndJoin()
        assertTrue(worker.isCancelled)
    }

    @Test fun staleIdentityIsRecoverableButInvalidInputIsNot() {
        assertTrue(canRecoverAgentRead(ApiRequestFailure("changed", 409, "account_changed")))
        assertTrue(canRecoverAgentRead(ApiRequestFailure("gone", 404, "run_not_found")))
        assertFalse(canRecoverAgentRead(ApiRequestFailure("invalid", 400)))
    }
}
