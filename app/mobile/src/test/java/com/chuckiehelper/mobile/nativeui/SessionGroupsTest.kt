package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionGroupsTest {
    private fun row(id: String, group: String?, active: Long, pinned: Boolean = false) =
        JSONObject().put("id", id).apply {
            if (group != null) put("group_name", group)
            put("last_active", active)
            put("pinned", pinned)
        }

    @Test fun groupsByServerNameAndOrdersByNewestSession() {
        // 输入约定：与 orderedSessions 一致（last_active 倒序、置顶在前），分组不改变组内顺序
        val rows = listOf(
            row("b", "ProjectB", 500),
            row("a", "ProjectA", 300),
            row("c", "ProjectA", 100),
            row("d", null, 50),
        )
        val sections = groupCodexSessions(rows) { it.optBoolean("pinned") }
        assertEquals(listOf("ProjectB", "ProjectA", "其他"), sections.map { it.name })
        assertEquals(listOf("b"), sections[0].sessions.map { it.optString("id") })
        assertEquals(listOf("a", "c"), sections[1].sessions.map { it.optString("id") })
    }

    @Test fun pinnedRowsFormTheirOwnFirstSectionKeepingOriginalOrder() {
        val rows = listOf(
            row("x", "ProjectA", 100),
            row("p1", "ProjectA", 900, pinned = true),
            row("p2", "ProjectB", 800, pinned = true),
        )
        val sections = groupCodexSessions(rows) { it.optBoolean("pinned") }
        assertEquals(2, sections.size)
        assertTrue(sections[0].pinned)
        assertEquals("已置顶", sections[0].name)
        assertEquals(listOf("p1", "p2"), sections[0].sessions.map { it.optString("id") })
        assertEquals(listOf("x"), sections[1].sessions.map { it.optString("id") })
    }

    @Test fun emptyInputYieldsNoSectionsAndMissingGroupFallsBack() {
        assertTrue(groupCodexSessions(emptyList()) { false }.isEmpty())
        val sections = groupCodexSessions(listOf(row("z", null, 10))) { it.optBoolean("pinned") }
        assertEquals(listOf("其他"), sections.map { it.name })
    }
}
