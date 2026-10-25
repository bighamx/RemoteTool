package com.chuckiehelper.mobile.nativeui

import java.io.EOFException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AgentOperationTest {
    @Test fun lateChildReadFailureIsReportedWithoutCrashingOrCancellingTheModelScope() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, e -> uncaught.add(e) })
        val failure = ReadConnectionFailure(EOFException("unexpected end of stream"))
        val reported = mutableListOf<Exception>()
        val release = CompletableDeferred<Unit>()
        var parentBodyReturned = false
        try {
            val operation = scope.launch {
                runAgentOperation({ reported.add(it) }) {
                    launch { release.await(); throw failure }
                    parentBodyReturned = true
                }
            }
            assertTrue(parentBodyReturned)
            assertFalse(operation.isCompleted)
            release.complete(Unit)
            operation.join()
            assertEquals(listOf(failure), reported)
            assertTrue(uncaught.isEmpty())
            assertTrue(scope.isActive)
            var nextRead = false
            scope.launch { nextRead = true }.join()
            assertTrue(nextRead)
        } finally { scope.cancel() }
    }

    @Test fun cancellationStillCancelsChildWorkAndDoesNotBecomeAnErrorBanner() = runBlocking {
        val child = CompletableDeferred<Job>()
        val errors = mutableListOf<Exception>()
        val operation = launch {
            runAgentOperation({ errors.add(it) }) {
                child.complete(launch { awaitCancellation() })
            }
        }
        val read = child.await()
        operation.cancelAndJoin()
        assertTrue(read.isCancelled)
        assertTrue(errors.isEmpty())
    }

    @Test fun readFailureNeverReplaysTheOperation() = runBlocking {
        var writes = 0
        var failures = 0
        runAgentOperation({ failures++ }) {
            writes++
            launch { yield(); throw ReadConnectionFailure(EOFException("connection closed")) }
        }
        assertEquals(1, writes)
        assertEquals(1, failures)
    }
}
