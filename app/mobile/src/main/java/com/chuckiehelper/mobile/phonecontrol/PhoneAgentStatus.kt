package com.chuckiehelper.mobile.phonecontrol

/** Full detail belongs in the expanded notification; status chips show the current action. */
internal object PhoneAgentStatus {
    fun errorText(value: String): String = when (value) {
        "operation_failed", "execution_failed" -> "操作失败"
        "timeout" -> "请求超时"
        "postcondition_timeout" -> "未等到预期结果"
        "delivery_unknown" -> "执行结果待确认"
        "permission_required", "permission_denied" -> "缺少所需权限"
        "cancelled", "connection_closed_by_user" -> "操作已取消"
        "device_busy", "ui_busy" -> "设备正在忙碌"
        "ui_ownership_required" -> "尚未取得界面控制权"
        "not_found" -> "目标不存在"
        "invalid_arguments" -> "操作参数无效"
        "disabled" -> "操控服务已停止"
        "phone_offline" -> "手机已离线"
        "read_only_backend" -> "当前操作方式只支持读取"
        else -> if (value.matches(Regex("[a-z][a-z0-9_]*"))) "操作未完成" else value
    }
    private fun focused(state: AgentDisplayState) = state.connections.firstOrNull { it.ownsUi }
        ?: state.connections.lastOrNull { it.operation.isNotEmpty() } ?: state.connections.lastOrNull()

    fun shortText(state: AgentDisplayState): String {
        val entry = focused(state)?.history?.lastOrNull()
        val text = when {
            state.notice?.help == true -> "等待协助"
            state.notice != null -> "Agent消息"
            entry?.status == "failed" -> "执行失败"
            entry?.status == "unknown" -> "结果待确认"
            !state.operating -> "已连接"
            state.operation == "界面已占用" -> "界面占用"
            else -> state.operation.removePrefix("正在").ifBlank { "执行任务" }
        }
        return text.codePoints().limit(7).toArray().let { String(it, 0, it.size) }
    }
    fun title(state: AgentDisplayState) = "${state.notice?.agent ?: state.agent} · ${shortText(state)}"
    fun detail(state: AgentDisplayState): String {
        state.notice?.let { return it.message }
        val client = focused(state)
        val entry = client?.history?.lastOrNull()
        val outcome = when (entry?.status) {
            "failed" -> listOf("执行失败", errorText(entry.error)).filter { it.isNotBlank() }.joinToString("：")
            "unknown" -> "结果待确认"
            else -> ""
        }
        // 标题已经带了当前动作名，这里只说“为什么做”，避免同一句话出现两遍。
        return listOf(outcome, client?.goal.orEmpty(), state.explanation)
            .filter { it.isNotBlank() }.distinct().joinToString(" · ")
            .ifBlank { "${state.agent} 已连接" }
    }
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
