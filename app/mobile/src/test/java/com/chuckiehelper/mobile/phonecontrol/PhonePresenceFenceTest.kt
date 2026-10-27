package com.chuckiehelper.mobile.phonecontrol
import org.junit.Assert.*
import org.junit.Test

class PhonePresenceFenceTest {
    @Test fun oldIdleCleanupCannotCloseResumedOwnerOrReviveAnExpiredTransport() {
        val fence = PhonePresenceFence()
        assertTrue(fence.accept("owner", 1, true))
        assertTrue(fence.accept("owner", 2, true))
        assertFalse(fence.canTouch("owner", 1))
        assertTrue(fence.canTouch("owner", 2))
        assertFalse(fence.accept("owner", 1, false))
        assertTrue(fence.accept("owner", 2, false))
        assertFalse(fence.canTouch("owner", 2))
        assertFalse(fence.accept("owner", 2, true))
        assertTrue(fence.accept("owner", 3, true))
        assertTrue(fence.accept("other", 1, true))
        fence.clear()
        assertTrue(fence.accept("owner", 1, true))
    }
}
