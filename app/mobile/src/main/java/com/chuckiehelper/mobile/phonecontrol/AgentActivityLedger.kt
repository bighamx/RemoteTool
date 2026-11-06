package com.chuckiehelper.mobile.phonecontrol

import java.util.UUID
import org.json.JSONObject

internal data class AgentUserNotice(val id: String, val owner: String, val agent: String, val message: String,
    val help: Boolean, val expiresAt: Long, var status: String = "pending")
internal data class AgentHistoryEntry(val label: String, val explanation: String, val at: Long,
    val status: String = "running", val operationId: String = "", val error: String = "") {
    val running get() = status == "running"
}
internal data class AgentConnectionState(val id: String, val name: String, val ownsUi: Boolean, val operation: String, val explanation: String,
    val goal: String = "", val notices: List<AgentUserNotice> = emptyList(), val history: List<AgentHistoryEntry> = emptyList())
internal data class AgentDisplayState(val connected: Boolean, val agent: String, val operating: Boolean,
    val operation: String, val notice: AgentUserNotice?, val explanation: String = "", val connections: List<AgentConnectionState> = emptyList())

/** Connections are independent. UI ownership ends explicitly, or after five idle minutes. */
internal class AgentActivityLedger(private val clock: () -> Long) {
    private data class Agent(var name: String, var seenUntil: Long, var goal: String = "",
        var operation: String = "", var explanation: String = "", var operationUntil: Long = 0,
        val history: MutableList<AgentHistoryEntry> = mutableListOf())
    private val agents = linkedMapOf<String, Agent>()
    private val notices = linkedMapOf<String, AgentUserNotice>()
    private var uiOwner = ""
    private var lastUi = 0L
    @Synchronized fun seen(id: String, name: String = "Agent") {
        expire()
        val clean = name.filter { !it.isISOControl() }.take(40).ifBlank { "Agent" }
        val row = agents.getOrPut(id) { Agent(clean, 0) }
        row.name = clean; row.seenUntil = clock() + 5 * 60_000L
        while (agents.size > 128) agents.keys.firstOrNull { it != uiOwner && it != id }?.let { agents.remove(it) } ?: break
    }
    @Synchronized fun touchConnection(id: String) { agents[id]?.seenUntil = clock() + 5 * 60_000L }
    @Synchronized fun disconnect(id: String) {
        agents.remove(id)
        if (uiOwner == id) uiOwner = ""
        notices.values.filter { it.owner == id && it.status == "pending" }.forEach { it.status = "cancelled" }
    }
    @Synchronized fun disconnectWeb() { agents.keys.filter { it.startsWith("web:") }.toList().forEach(::disconnect) }
    @Synchronized fun beginUi(id: String, message: String, duration: Int) {
        expire()
        if (uiOwner.isNotEmpty() && uiOwner != id) busy()
        if (uiOwner != id) { uiOwner = id; lastUi = clock() }
        agents[id]?.goal = message.take(160)
    }
    @Synchronized fun requireUi(id: String) {
        expire()
        if (uiOwner.isEmpty()) fail("ui_ownership_required", "请先调用 phone_begin_ui 获取界面占用")
        if (uiOwner != id) busy()
        lastUi = clock(); touchConnection(id)
    }
    private fun busy(): Nothing = throw PhoneToolError("ui_busy", "界面正由 ${agents[uiOwner]?.name ?: "其他 Agent"} 占用", JSONObject()
        .put("owner", uiOwner).put("owner_name", agents[uiOwner]?.name ?: "Agent")
        .put("idle_release_after_ms", (300_000L - (clock() - lastUi)).coerceAtLeast(0)))
    @Synchronized fun endUi(id: String) {
        expire()
        if (uiOwner.isNotEmpty() && uiOwner != id) busy()
        if (uiOwner == id) uiOwner = ""
        agents[id]?.let { it.goal = ""; it.operation = "" }
    }
    @Synchronized fun toolStarted(id: String, label: String, purpose: String = "", operationId: String = "") {
        agents[id]?.let {
            val explanation = purpose.filter { ch -> !ch.isISOControl() || ch == '\n' }.trim().take(240)
            it.operation = label; it.explanation = explanation
            it.operationUntil = clock() + 130_000
            // 最近运行的命令留三条：同一命令的连续刷新只更新那一条，不重复占位。
            val last = it.history.lastOrNull()
            if (last != null && last.label == label && last.explanation == explanation && last.running && last.operationId == operationId) {
                it.history[it.history.lastIndex] = last.copy(at = clock())
            } else {
                it.history.add(AgentHistoryEntry(label, explanation, clock(), operationId = operationId))
                while (it.history.size > 3) it.history.removeAt(0)
            }
        }
    }
    @Synchronized fun toolFinished(id: String, operationId: String = "", status: String = "completed", error: String = "") {
        if (operationId.isBlank()) return
        agents[id]?.let { agent ->
            val index = agent.history.indexOfLast { it.operationId == operationId }
            if (index >= 0) {
                val entry = agent.history[index]
                agent.history[index] = entry.copy(status = status,
                    error = error.filter { !it.isISOControl() }.take(100))
                if (index == agent.history.lastIndex) agent.operationUntil = clock() + 2_000
            }
        }
    }
    @Synchronized fun notify(id: String, message: String, help: Boolean, duration: Int): AgentUserNotice {
        val notice = AgentUserNotice(UUID.randomUUID().toString(), id, agents[id]?.name ?: "Agent", message.take(1000), help,
            clock() + duration.coerceIn(5, 1800) * 1000L)
        notices[notice.id] = notice
        while (notices.size > 32) notices.remove(notices.keys.first())
        return notice.copy()
    }
    @Synchronized fun reply(id: String, status: String) {
        expire()
        notices[id]?.takeIf { it.status == "pending" }?.let { if (status in setOf("done", "dismissed")) it.status = status }
    }
    @Synchronized fun result(owner: String, id: String): AgentUserNotice? { expire(); return notices[id]?.takeIf { it.owner == owner }?.copy() }
    @Synchronized fun snapshot(): AgentDisplayState {
        expire()
        val now = clock()
        val rows = agents.map { (id, row) -> AgentConnectionState(id, row.name, id == uiOwner,
            row.operation.takeIf { row.operationUntil > now }.orEmpty(), row.explanation.takeIf { row.operationUntil > now }.orEmpty(),
            row.goal.takeIf { id == uiOwner }.orEmpty(), notices.values.filter { it.owner == id && it.status == "pending" }.map { it.copy() },
            row.history.toList()) }
        val focused = rows.firstOrNull { it.ownsUi } ?: rows.lastOrNull { it.operation.isNotEmpty() || it.explanation.isNotEmpty() } ?: rows.lastOrNull()
        val operation = focused?.operation.orEmpty().ifBlank { agents[uiOwner]?.goal.orEmpty().ifBlank { if (uiOwner.isNotEmpty()) "界面已占用" else "" } }
        return AgentDisplayState(rows.isNotEmpty(), focused?.name ?: "Agent", uiOwner.isNotEmpty() || rows.any { it.operation.isNotEmpty() }, operation,
            (notices.values.lastOrNull { it.status == "pending" && it.help }
                ?: notices.values.lastOrNull { it.status == "pending" })?.copy(), focused?.explanation.orEmpty(), rows)
    }
    @Synchronized fun clear() {
        agents.clear(); uiOwner = ""
        notices.values.filter { it.status == "pending" }.forEach { it.status = "cancelled" }
    }
    private fun expire() {
        val now = clock()
        agents.values.forEach { agent ->
            agent.history.indices.forEach { index ->
                val entry = agent.history[index]
                if (entry.running && now - entry.at >= 130_000)
                    agent.history[index] = entry.copy(status = "unknown", error = "尚未收到执行结果")
            }
        }
        if (uiOwner.isNotEmpty() && now - lastUi >= 300_000L) { agents[uiOwner]?.goal = ""; uiOwner = "" }
        notices.values.filter { it.status == "pending" && it.expiresAt <= now }.forEach { it.status = "expired" }
        agents.filter { it.key != uiOwner && it.value.seenUntil <= now }.keys.toList().forEach(::disconnect)
    }
}
