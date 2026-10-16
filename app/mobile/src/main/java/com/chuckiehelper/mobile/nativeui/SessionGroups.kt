package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

/** Codex 会话列表按项目分组的纯数据结构：pinned=true 的节固定排最前。 */
data class SessionGroup(val name: String, val sessions: List<JSONObject>, val pinned: Boolean = false)

/**
 * 把已排序（last_active 倒序、置顶在前）的 codex 会话行按服务端 group_name 分节。
 * 组间按组内最新 last_active 倒序；group_name 缺失（cwd 为空的旧行）归入"其他"。
 * rows 本身的顺序决定组内顺序，这里不重排会话。
 */
fun groupCodexSessions(rows: List<JSONObject>, isPinned: (JSONObject) -> Boolean): List<SessionGroup> {
    // 已存档会话单独一节沉底（置顶/项目节都不含它们）
    val pinnedRows = rows.filter { isPinned(it) && !it.optBoolean("archived") }
    val groups = LinkedHashMap<String, MutableList<JSONObject>>()
    for (row in rows.filterNot { isPinned(it) || it.optBoolean("archived") }) {
        val name = row.optString("group_name").ifBlank { "其他" }
        groups.getOrPut(name) { mutableListOf() }.add(row)
    }
    fun newest(group: SessionGroup) = group.sessions.maxOfOrNull { parseMessageTimestamp(it.opt("last_active")) ?: 0L } ?: 0L
    val sections = mutableListOf<SessionGroup>()
    if (pinnedRows.isNotEmpty()) sections += SessionGroup("已置顶", pinnedRows, pinned = true)
    sections += groups.entries.map { (name, list) -> SessionGroup(name, list.toList()) }.sortedByDescending(::newest)
    val archived = rows.filter { it.optBoolean("archived") }
    if (archived.isNotEmpty()) sections += SessionGroup("已存档", archived)
    return sections
}
