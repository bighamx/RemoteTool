package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class MessageTimeTest {
    private val zone = ZoneId.of("Asia/Hong_Kong")
    @Test fun parsesSecondsMillisecondsAndIsoOffsetsWithoutInventingMissingTimes() {
        val expected = Instant.parse("2026-10-05T01:23:00Z").toEpochMilli()
        assertEquals(expected, parseMessageTimestamp(expected))
        assertEquals(expected, parseMessageTimestamp(expected / 1000.0))
        assertEquals(expected, parseMessageTimestamp("2026-10-05T09:23:00+08:00"))
        assertNull(parseMessageTimestamp("null"))
        assertNull(parseMessageTimestamp("invalid"))
        assertNull(parseMessageTimestamp(0))
        assertEquals("", formatMessageTimestamp(null))
    }
    @Test fun dateBoundariesUseThePhonesTimeZone() {
        val now = Instant.parse("2026-10-05T01:24:00Z")
        assertEquals("09:23", formatMessageTimestamp(Instant.parse("2026-10-05T01:23:00Z").toEpochMilli(), now, zone))
        assertEquals("昨天 23:59", formatMessageTimestamp(Instant.parse("2026-10-04T15:59:00Z").toEpochMilli(), now, zone))
        assertEquals("10-03 09:23", formatMessageTimestamp(Instant.parse("2026-10-03T01:23:00Z").toEpochMilli(), now, zone))
        assertEquals("2025-10-05 09:23", formatMessageTimestamp(Instant.parse("2025-10-05T01:23:00Z").toEpochMilli(), now, zone))
    }
}
