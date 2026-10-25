package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class FileModifiedTimeTest {
    @Test fun serverOffsetConvertsToPhoneTimezone() {
        assertEquals("2026-10-07 14:23", formatFileModifiedTime("2026-10-07T06:23:45.1234567Z", ZoneId.of("Asia/Hong_Kong")))
    }
    @Test fun localTimestampKeepsServerWallClock() {
        assertEquals("2026-10-07 14:23", formatFileModifiedTime("2026-10-07T14:23:45.1234567"))
        assertEquals("", formatFileModifiedTime(""))
        assertEquals("", formatFileModifiedTime("invalid"))
    }
}
