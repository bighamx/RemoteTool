package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class ChatFollowMotionTest {
    @Test fun growthConvergesWithoutOvershoot() {
        var remaining = 160f
        repeat(90) {
            val step = chatFollowStep(remaining, 16.667f, 800f)
            assertTrue(step >= 0f && step <= remaining)
            remaining -= step
        }
        assertEquals(0f, remaining, 0.01f)
    }
    @Test fun refreshRateDoesNotChangeFollowingSpeed() {
        fun remaining(frame: Float, count: Int): Float {
            var distance = 300f
            repeat(count) { distance -= chatFollowStep(distance, frame, 900f) }
            return distance
        }
        assertEquals(remaining(16.667f, 12), remaining(8.3335f, 24), 0.1f)
    }
    @Test fun largeBubblesCannotJumpWholeViewport() {
        assertTrue(chatFollowStep(5000f, 16.667f, 800f) < 80f)
        assertEquals(0f, chatFollowStep(0f, 16f, 800f), 0f)
        assertEquals(0f, chatFollowStep(100f, 16f, 0f), 0f)
    }
}
