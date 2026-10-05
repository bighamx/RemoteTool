package com.chuckiehelper.mobile.nativeui

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToLong

fun parseMessageTimestamp(value: Any?): Long? {
    if (value == null || value.toString().isBlank() || value.toString() == "null") return null
    val numeric = value.toString().toDoubleOrNull()
    if (numeric != null) {
        if (!numeric.isFinite() || numeric <= 0) return null
        return runCatching {
            val milliseconds = (if (numeric < 100_000_000_000.0) numeric * 1000 else numeric).roundToLong()
            Instant.ofEpochMilli(milliseconds)
            milliseconds
        }.getOrNull()
    }
    return runCatching { Instant.parse(value.toString()).toEpochMilli() }.getOrElse {
        runCatching { OffsetDateTime.parse(value.toString()).toInstant().toEpochMilli() }.getOrNull()
    }
}

fun formatMessageTimestamp(timestamp: Long?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
    if (timestamp == null) return ""
    return runCatching {
        val time = Instant.ofEpochMilli(timestamp).atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        val clock = time.format(DateTimeFormatter.ofPattern("HH:mm"))
        when {
            time.toLocalDate() == today -> clock
            time.toLocalDate() == today.minusDays(1) -> "昨天 $clock"
            time.year == today.year -> time.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            else -> time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }
    }.getOrDefault("")
}
