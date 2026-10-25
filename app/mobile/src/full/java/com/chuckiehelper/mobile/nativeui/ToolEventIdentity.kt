package com.chuckiehelper.mobile.nativeui

internal fun completeToolEvent(rows: List<HermesEvent>, name: String, itemId: String?, failed: Boolean): List<HermesEvent> {
    val matching = rows.withIndex().filter { (_, row) -> row.type == "执行中" &&
        (if (itemId != null) row.itemId == itemId else row.itemId == null && row.text == name) }
    // Without an ID, two parallel tools of the same name are ambiguous.
    // A completion was observed, but neither row can truthfully remain "running".
    if (itemId == null && matching.size > 1) return rows.map { row ->
        if (row.type == "执行中" && row.itemId == null && row.text == name)
            row.copy(type = "状态待核对") else row
    } + HermesEvent("进度", "$name 有调用已${if (failed) "失败" else "完成"}，无法确定具体条目")
    val index = if (itemId != null) matching.lastOrNull()?.index else matching.singleOrNull()?.index
    return if (index == null) rows else rows.mapIndexed { i, row ->
        if (i == index) row.copy(type = if (failed) "失败" else "完成") else row
    }
}
