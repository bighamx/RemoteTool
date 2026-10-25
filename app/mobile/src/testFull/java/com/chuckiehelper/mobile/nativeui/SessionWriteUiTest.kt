package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class SessionWriteUiTest {
    @Test fun idleWriterDoesNotOfferInterruption() {
        assertFalse(showWriteInterruption(false, "writer_held", 1000, 2000))
        assertFalse(showWriteInterruption(false, "available", 1000, 2000))
        assertFalse(showWriteInterruption(false, "mobile", 1000, 2000))
    }
    @Test fun verifiedRunningTaskOffersInterruption() {
        assertTrue(showWriteInterruption(true, "writer_held", 0, 2000))
        assertTrue(showWriteInterruption(false, "busy", 1000, 2000))
    }
    @Test fun staleControlDoesNotOfferInterruption() {
        assertFalse(showWriteInterruption(false, "busy", 1000, 31000))
        assertFalse(showWriteInterruption(false, "busy", 0, 2000))
    }
}
