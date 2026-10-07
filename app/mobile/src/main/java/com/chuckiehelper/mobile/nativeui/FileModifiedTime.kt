package com.chuckiehelper.mobile.nativeui

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun formatFileModifiedTime(raw: String, zone: ZoneId = ZoneId.systemDefault()): String {
    if (raw.isBlank()) return ""
    val time = runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(zone).toLocalDateTime() }
        .getOrElse { runCatching { LocalDateTime.parse(raw) }.getOrNull() } ?: return ""
    return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}
