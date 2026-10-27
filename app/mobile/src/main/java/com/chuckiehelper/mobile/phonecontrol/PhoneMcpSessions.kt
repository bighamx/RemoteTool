package com.chuckiehelper.mobile.phonecontrol

internal class PhoneMcpSessions(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    data class Client(val id: String, val name: String, var touched: Long, val logicalId: String = id,
        val resumeToken: String = java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID().toString(), val generation: Int = 1) {
        val owner get() = "direct:$logicalId"
        internal var active = 0
    }
    private val clients = linkedMapOf<String, Client>()
    private val recoverable = linkedMapOf<String, Client>()
    @Synchronized fun create(name: String, resumeToken: String? = null, mayResume: (String) -> Boolean = { true }): Client {
        expire()
        val previous = resumeToken?.let { recoverable[it] ?: fail("resume_unknown", "恢复凭据未知、失效或已撤销；未创建新身份，需要显式重新授权") }
        if (previous != null) {
            if (!mayResume(previous.owner)) fail("connection_closed_by_user", "用户已断开此操作者，不能自动恢复")
            if (previous.active > 0) fail("device_busy", "旧连接仍有操作在执行，请先等待回执")
            clients.remove(previous.id)
        }
        if (clients.size >= 128) fail("device_busy", "MCP 连接数量已达上限")
        val id = java.util.UUID.randomUUID().toString()
        return Client(id, name.filter { !it.isISOControl() }.take(40).ifBlank { "Agent" }, clock(),
            previous?.logicalId ?: id, previous?.resumeToken ?: (java.util.UUID.randomUUID().toString() + java.util.UUID.randomUUID()), (previous?.generation ?: 0) + 1)
            .also { clients[it.id] = it; recoverable[it.resumeToken] = it }
    }
    @Synchronized fun get(id: String): Client? { expire(); return clients[id]?.also { it.touched = clock() } }
    @Synchronized fun remove(id: String): Client? = clients[id]?.let(::removeOwned)
    @Synchronized fun removeByResumeToken(token: String): Client? {
        expire()
        return recoverable[token]?.let(::removeOwned)
    }
    private fun removeOwned(client: Client): Client {
        if (client.active != 0) fail("device_busy", "操作仍在执行，请等待回执后清理该操作者")
        clients.remove(client.id); recoverable.remove(client.resumeToken)
        return client
    }
    fun <T> withClient(client: Client, block: () -> T): T {
        synchronized(this) {
            if (clients[client.id] !== client) fail("session_replaced", "此传输会话已被恢复后的新会话替代")
            client.active++; client.touched = clock()
        }
        try { return block() } finally { synchronized(this) { client.active--; client.touched = clock() } }
    }
    private fun expire() {
        clients.entries.removeAll { it.value.active == 0 && clock() - it.value.touched >= 5 * 60_000L }
        recoverable.entries.removeAll { it.value.active == 0 && clock() - it.value.touched > 24 * 60 * 60_000L }
        while (recoverable.size > 256) { val old = recoverable.entries.firstOrNull { it.value.active == 0 && it.value.id !in clients } ?: break; recoverable.remove(old.key) }
    }
}
