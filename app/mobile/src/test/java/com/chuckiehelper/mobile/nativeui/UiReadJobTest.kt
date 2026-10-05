package com.chuckiehelper.mobile.nativeui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UiReadJobTest {
    @Test fun leavingScreenCancelsItsInflightViewModelRead() = runBlocking {
        val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val read = vmScope.launch { awaitCancellation() }
            val ui = launch(start = CoroutineStart.UNDISPATCHED) { awaitUiRead(read) }
            ui.cancelAndJoin()
            assertTrue(read.isCancelled)
            val completed = vmScope.launch { }
            completed.join()
            awaitUiRead(completed)
            assertFalse(completed.isCancelled)
        } finally { vmScope.cancel() }
    }
}
