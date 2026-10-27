package com.chuckiehelper.mobile.phonecontrol

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PhoneOperationAdmissionTest {
    @Test fun persistenceDoesNotHoldConnectionGateAndChecksBracketIt() {
        val gate = Any(); val order = mutableListOf<String>()
        PhoneOperationAdmission.begin(gate,
            { assertTrue(Thread.holdsLock(gate)); order.add("check") },
            { assertFalse(Thread.holdsLock(gate)); order.add("persist") },
            { assertTrue(Thread.holdsLock(gate)); order.add("start") })
        assertEquals(listOf("check", "persist", "check", "start"), order)
    }
    @Test fun stopDuringPersistenceCanAcquireGateAndPreventsExecution() {
        val gate = Any(); val blocked = AtomicBoolean(); val started = AtomicBoolean()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit {
                PhoneOperationAdmission.begin(gate,
                    { check(!blocked.get()) { "stopped" } },
                    { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) },
                    { started.set(true) })
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            synchronized(gate) { blocked.set(true) }
            release.countDown()
            val failure = assertThrows(ExecutionException::class.java) { task.get(2, TimeUnit.SECONDS) }
            assertTrue(failure.cause is IllegalStateException)
            assertEquals("stopped", failure.cause!!.message)
            assertFalse(started.get())
        } finally { release.countDown(); worker.shutdownNow() }
    }
    @Test fun persistenceFailureNeverCallsExecutionMarker() {
        val started = AtomicBoolean()
        assertThrows(IllegalStateException::class.java) {
            PhoneOperationAdmission.begin(Any(), {}, { error("disk error") }, { started.set(true) })
        }
        assertFalse(started.get())
    }
    @Test fun alreadyStoppedCallDoesNotWriteJournalOrStart() {
        val persisted = AtomicBoolean(); val started = AtomicBoolean()
        assertThrows(IllegalStateException::class.java) {
            PhoneOperationAdmission.begin(Any(), { error("stopped") }, { persisted.set(true) }, { started.set(true) })
        }
        assertFalse(persisted.get()); assertFalse(started.get())
    }
}
