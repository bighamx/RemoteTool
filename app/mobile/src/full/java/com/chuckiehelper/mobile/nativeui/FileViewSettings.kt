package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale

internal data class FileViewSettings(val sort: FileSort = FileSort.NAME, val secondarySort: FileSort? = null,
    val descending: Boolean = false, val secondaryDescending: Boolean = false, val foldersFirst: Boolean = true)

internal fun sortRemoteFiles(files: List<JSONObject>, settings: FileViewSettings): List<JSONObject> {
    data class Entry(val row: JSONObject, val name: String, val type: String, val time: Long?,
        val isDisk: Boolean = false)
    val needsTime = settings.sort == FileSort.MODIFIED || settings.secondarySort == FileSort.MODIFIED
    val entries = files.map { row ->
        val name = row.optString("name").lowercase(Locale.ROOT)
        val raw = row.optString("modified")
        val time = if (!needsTime) null else
            runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(raw).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
        val isDisk = row.has("totalBytes")
        Entry(row, name, if (name.lastIndexOf('.') > 0) name.substringAfterLast('.') else "", time, isDisk)
    }
    fun compareKey(a: Entry, b: Entry, key: FileSort): Int = when (key) {
        FileSort.NAME -> a.name.compareTo(b.name)
        FileSort.TYPE -> a.type.compareTo(b.type)
        FileSort.SIZE -> a.row.optLong("size").compareTo(b.row.optLong("size"))
        FileSort.MODIFIED -> compareValues(a.time, b.time)
    }
    return entries.sortedWith { a, b ->
        val grouping = if (settings.foldersFirst) b.row.optBoolean("isDirectory").compareTo(a.row.optBoolean("isDirectory")) else 0
        if (grouping != 0) grouping else if (a.isDisk && b.isDisk) {
            // 磁盘列表固定按盘符顺序（名称以盘符开头），不受视图排序设置影响。
            a.name.compareTo(b.name)
        } else {
            // Missing modification times stay last in either direction.
            val missing = if (needsTime) (a.time == null).compareTo(b.time == null) else 0
            if (missing != 0) missing else {
                val primary = compareKey(a, b, settings.sort)
                val secondary = if (primary == 0 && settings.secondarySort != null && settings.secondarySort != settings.sort) {
                    val compared = compareKey(a, b, settings.secondarySort!!)
                    if (settings.secondaryDescending) -compared else compared
                } else 0
                val compared = primary.takeIf { it != 0 } ?: secondary.takeIf { it != 0 }
                    ?: a.name.compareTo(b.name).takeIf { it != 0 }
                    ?: a.row.optString("path").compareTo(b.row.optString("path"))
                if (settings.descending) -compared else compared
            }
        }
    }.map { it.row }
}
