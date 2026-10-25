package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class SseGapTest {
    @Test fun expiredReplayWindowCannotBeAppendedAsContinuousDelta() {
        assertTrue(missingSseEvents(1, 45))
        assertFalse(missingSseEvents(44, 45))
        assertFalse(missingSseEvents(-1, 45)) // Initial connect uses the server's explicit gap marker.
        assertFalse(missingSseEvents(45, 45))
    }
}
