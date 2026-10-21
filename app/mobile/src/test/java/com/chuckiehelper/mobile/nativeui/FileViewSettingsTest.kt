package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FileViewSettingsTest {
    private fun row(name: String, directory: Boolean = false, size: Long = 0, modified: String = "") =
        JSONObject().put("name", name).put("path", "/$name").put("isDirectory", directory).put("size", size).put("modified", modified)
    private fun disk(label: String, letter: String) = JSONObject()
        .put("name", "$letter ($label)").put("path", "$letter/").put("isDirectory", true)
        .put("totalBytes", 1L).put("freeBytes", 0L)
    private fun names(rows: List<JSONObject>, settings: FileViewSettings) = sortRemoteFiles(rows, settings).map { it.getString("name") }

    @Test fun directoriesRemainFirstInBothDirectionsButCanBeMixed() {
        val rows = listOf(row("b.txt"), row("z", true), row("a", true), row("a.txt"))
        assertEquals(listOf("a", "z", "a.txt", "b.txt"), names(rows, FileViewSettings()))
        assertEquals(listOf("z", "a", "b.txt", "a.txt"), names(rows, FileViewSettings(descending = true)))
        assertEquals(listOf("z", "b.txt", "a.txt", "a"), names(rows, FileViewSettings(descending = true, foldersFirst = false)))
        assertEquals("b.txt", rows[0].getString("name")) // Sorting never mutates API/cache order.
    }
    @Test fun sizesUseLongsAndTypesUseExtensions() {
        val rows = listOf(row("b.zip", size = 9_000_000_000L), row("c.PDF", size = 1), row("a.pdf", size = 2))
        assertEquals(listOf("c.PDF", "a.pdf", "b.zip"), names(rows, FileViewSettings(FileSort.SIZE)))
        assertEquals(listOf("b.zip", "a.pdf", "c.PDF"), names(rows, FileViewSettings(FileSort.SIZE, descending = true)))
        assertEquals(listOf("a.pdf", "c.PDF", "b.zip"), names(rows, FileViewSettings(FileSort.TYPE)))
    }
    @Test fun timestampsRespectOffsetsAndMissingTimesStayLast() {
        val rows = listOf(row("old", modified = "2026-10-18T10:00:00+08:00"),
            row("new", modified = "2026-10-18T03:00:00Z"), row("missing"), row("invalid", modified = "invalid"))
        assertEquals(listOf("old", "new", "invalid", "missing"), names(rows, FileViewSettings(FileSort.MODIFIED)))
        assertEquals(listOf("new", "old", "missing", "invalid"), names(rows, FileViewSettings(FileSort.MODIFIED, descending = true)))
    }
    @Test fun secondarySortBreaksPrimaryTies() {
        val rows = listOf(row("c.txt", size = 5), row("a.pdf", size = 5), row("b.txt", size = 1))
        // 主排序=大小，次要=类型：大小并列的 5 按扩展名 pdf < txt。
        assertEquals(listOf("b.txt", "a.pdf", "c.txt"), names(rows, FileViewSettings(FileSort.SIZE, secondarySort = FileSort.TYPE)))
        // 次要排序与主排序相同或为空时回落到名称。
        assertEquals(listOf("b.txt", "a.pdf", "c.txt"), names(rows, FileViewSettings(FileSort.SIZE, secondarySort = FileSort.SIZE)))
        assertEquals(listOf("b.txt", "a.pdf", "c.txt"), names(rows, FileViewSettings(FileSort.SIZE, secondarySort = null)))
        // 次要=修改时间：缺失时间的行仍保持在该键内垫底。
        val timed = listOf(row("x", modified = "2026-10-18T10:00:00+08:00"), row("a", size = 1), row("b", size = 1))
        assertEquals(listOf("x", "a", "b"), names(timed, FileViewSettings(FileSort.SIZE, secondarySort = FileSort.MODIFIED)))
        // 次要排序有独立方向：主排序=类型升序（pdf<txt），次要=名称降序。
        val mixed = listOf(row("a.pdf"), row("b.pdf"), row("c.txt"))
        assertEquals(listOf("b.pdf", "a.pdf", "c.txt"),
            names(mixed, FileViewSettings(FileSort.TYPE, secondarySort = FileSort.NAME, secondaryDescending = true)))
    }
    @Test fun disksAlwaysSortByVolumeLabel() {
        val rows = listOf(disk("备份", "D:"), disk("系统", "C:"), disk("媒体", "H:"), disk("备份", "E:"))
        // 不论主排序是什么，磁盘都按盘符排。
        assertEquals(listOf("C: (系统)", "D: (备份)", "E: (备份)", "H: (媒体)"), names(rows, FileViewSettings()))
        assertEquals(listOf("C: (系统)", "D: (备份)", "E: (备份)", "H: (媒体)"),
            names(rows, FileViewSettings(FileSort.SIZE, descending = true)))
    }
}
