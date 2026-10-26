package com.chuckiehelper.mobile.phonecontrol

internal class PhoneMcpSessions(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    data class Client(val id: String, val name: String, var touched: Long) { val owner get() = "direct:$id" }
    private val clients = linkedMapOf<String, Client>()
    @Synchronized fun create(name: String): Client {
        expire()
        if (clients.size >= 128) fail("device_busy", "MCP 连接数量已达上限")
        return Client(java.util.UUID.randomUUID().toString(), name.filter { !it.isISOControl() }.take(40).ifBlank { "Agent" }, clock())
            .also { clients[it.id] = it }
    }
    @Synchronized fun get(id: String): Client? { expire(); return clients[id]?.also { it.touched = clock() } }
    @Synchronized fun remove(id: String): Client? = clients.remove(id)
    private fun expire() { clients.entries.removeAll { clock() - it.value.touched > 30 * 60_000L } }
}
