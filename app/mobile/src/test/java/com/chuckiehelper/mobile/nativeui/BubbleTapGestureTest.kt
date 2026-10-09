package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class BubbleTapGestureTest {
    @Test fun shortStationaryTouchOpensOnlyAtRelease() {
        val tap = BubbleTapGesture(8f, 500)
        tap.down(10f, 10f, 100)
        assertTrue(tap.up(13f, 12f, 180))
        assertFalse(tap.up(13f, 12f, 190))
    }
    @Test fun scrollCannotBecomeTapEvenWhenFingerReturnsToStart() {
        val tap = BubbleTapGesture(8f, 500)
        tap.down(0f, 0f, 100); tap.move(0f, 30f)
        assertFalse(tap.up(0f, 0f, 180))
    }
    @Test fun longPressAndCancelledOrMultiplePointerGesturesNeverOpenMenu() {
        val tap = BubbleTapGesture(8f, 500)
        tap.down(0f, 0f, 100)
        assertFalse(tap.up(0f, 0f, 600))
        tap.down(0f, 0f, 700); tap.cancel()
        assertFalse(tap.up(0f, 0f, 750))
    }
}
