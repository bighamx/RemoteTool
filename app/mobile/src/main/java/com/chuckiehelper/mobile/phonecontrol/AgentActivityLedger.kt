package com.chuckiehelper.mobile.phonecontrol

import java.util.UUID

internal data class AgentUserNotice(val id: String, val owner: String, val agent: String, val message: String,
    val help: Boolean, val expiresAt: Long, var status: String = "pending")
internal data class AgentDisplayState(val connected: Boolean, val agent: String, val operating: Boolean,
    val operation: String, val notice: AgentUserNotice?, val explanation: String = "")

/** Stateless MCP clients have activity leases, rather than a fabricated permanent connection. */
internal class AgentActivityLedger(private val clock: () -> Long) {
    private data class Agent(val name: String, var seenUntil: Long, var uiUntil: Long = 0, var goal: String = "")
    private val agents = linkedMapOf<String, Agent>()
    private val notices = linkedMapOf<String, AgentUserNotice>()
    private var operationOwner = ""
    private var operation = ""
    private var explanation = ""
    private var operationUntil = 0L

    @Synchronized fun seen(id: String, name: String = "Agent") {
        val now = clock()
        val old = agents.remove(id)
        agents[id] = Agent(name.filter { !it.isISOControl() }.take(40).ifBlank { "Agent" }, now + 120_000,
            old?.uiUntil ?: 0, old?.goal.orEmpty())
        while (agents.size > 16) agents.remove(agents.keys.first())
    }
    @Synchronized fun disconnect(id: String) {
        agents.remove(id)
        if (operationOwner == id) { operationOwner = ""; operation = ""; explanation = ""; operationUntil = 0 }
        notices.values.filter { it.owner == id && it.status == "pending" }.forEach { it.status = "cancelled" }
    }
    @Synchronized fun disconnectWeb() { agents.keys.filter { it.startsWith("web:") }.toList().forEach(::disconnect) }
    @Synchronized fun beginUi(id: String, message: String, duration: Int) {
        val row = agents[id] ?: return
        row.uiUntil = clock() + duration.coerceIn(15, 600) * 1000L
        row.goal = message.take(160)
    }
    @Synchronized fun endUi(id: String) {
        agents[id]?.let { it.uiUntil = 0; it.goal = "" }
        if (operationOwner == id) {
            val completingWithExplanation = operation.isEmpty() && explanation.isNotBlank()
            operation = ""
            if (!completingWithExplanation) operationUntil = 0
        }
    }
    @Synchronized fun toolStarted(id: String, label: String, purpose: String = "") {
        operationOwner = id; operation = label
        explanation = purpose.filter { !it.isISOControl() || it == '\n' }.trim().take(240)
        operationUntil = clock() + 40_000
    }
    @Synchronized fun toolFinished(id: String) {
        if (operationOwner == id) operationUntil = clock() + 2_000
    }
    @Synchronized fun notify(id: String, message: String, help: Boolean, duration: Int): AgentUserNotice {
        val notice = AgentUserNotice(UUID.randomUUID().toString(), id, agents[id]?.name ?: "Agent",
            message.take(1000), help, clock() + duration.coerceIn(5, 1800) * 1000L)
        notices[notice.id] = notice
        while (notices.size > 32) notices.remove(notices.keys.first())
        return notice.copy()
    }
    @Synchronized fun reply(id: String, status: String) {
        expire()
        val row = notices[id]?.takeIf { it.status == "pending" } ?: return
        if (status in setOf("done", "dismissed")) row.status = status
    }
    @Synchronized fun result(owner: String, id: String): AgentUserNotice? { expire(); return notices[id]?.takeIf { it.owner == owner }?.copy() }
    @Synchronized fun snapshot(): AgentDisplayState {
        expire()
        val now = clock()
        val active = agents.values.lastOrNull { it.seenUntil > now || it.uiUntil > now }
        val declared = agents.values.lastOrNull { it.uiUntil > now }
        val op = operation.takeIf { operationUntil > now && agents.containsKey(operationOwner) }.orEmpty()
        val purpose = explanation.takeIf { operationUntil > now && agents.containsKey(operationOwner) }.orEmpty()
        val name = (if (op.isNotEmpty() || purpose.isNotEmpty()) agents[operationOwner]?.name else null) ?: declared?.name ?: active?.name ?: "Agent"
        return AgentDisplayState(active != null, name, declared != null || op.isNotEmpty(),
            op.ifBlank { declared?.goal.orEmpty().ifBlank { if (declared != null) "正在操作屏幕" else "" } },
            notices.values.lastOrNull { it.status == "pending" }?.copy(), purpose)
    }
    @Synchronized fun clear() {
        agents.clear(); operationOwner = ""; operation = ""; explanation = ""; operationUntil = 0
        notices.values.filter { it.status == "pending" }.forEach { it.status = "cancelled" }
    }
    private fun expire() {
        val now = clock()
        notices.values.filter { it.status == "pending" && it.expiresAt <= now }.forEach { it.status = "expired" }
        agents.entries.removeAll { it.value.seenUntil <= now && it.value.uiUntil <= now }
    }
}
