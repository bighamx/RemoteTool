package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class MessageFooterLayoutTest {
    @Test fun shortMessagesKeepTimeOnTheirLastLine() {
        assertTrue(messageFooterFits(30f, 72, 34f, 8f))
        assertTrue(messageFooterFits(90f, 260, 34f, 8f))
    }
    @Test fun fullLastLineReservesASeparateFooterInsteadOfOverlappingText() {
        assertFalse(messageFooterFits(240f, 260, 34f, 8f))
        assertFalse(messageFooterFits(120f, 140, 60f, 8f))
    }
}
