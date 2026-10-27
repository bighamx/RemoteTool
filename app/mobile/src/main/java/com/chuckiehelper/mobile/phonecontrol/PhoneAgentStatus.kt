package com.chuckiehelper.mobile.phonecontrol

/** Full detail belongs in the expanded notification; status chips show the current action. */
internal object PhoneAgentStatus {
    fun shortText(state: AgentDisplayState): String {
        val text = when {
            state.notice?.help == true -> "等待协助"
            state.notice != null -> "Agent消息"
            !state.operating -> "已连接"
            state.operation == "界面已占用" -> "界面占用"
            else -> state.operation.removePrefix("正在").ifBlank { "执行任务" }
        }
        return text.codePoints().limit(7).toArray().let { String(it, 0, it.size) }
    }
    fun title(state: AgentDisplayState) = "${state.notice?.agent ?: state.agent} · ${shortText(state)}"
    fun detail(state: AgentDisplayState): String = state.notice?.message ?: listOf(
        state.connections.firstOrNull { it.ownsUi }?.goal.orEmpty(), state.operation, state.explanation
    ).filter { it.isNotBlank() }.distinct().joinToString(" · ").ifBlank { "${state.agent} 已连接" }
    // Budget each region separately; keep names compact and leave more room for the action.
    fun islandText(value: String, maxUnits: Int = 8): String {
        val text = StringBuilder(); var units = 0
        for (point in value.codePoints().toArray()) {
            val width = if (point in 0x20..0x7e) 1 else 2
            if (units + width > maxUnits) break
            if (!Character.isISOControl(point)) { text.appendCodePoint(point); units += width }
        }
        return text.toString()
    }
}
