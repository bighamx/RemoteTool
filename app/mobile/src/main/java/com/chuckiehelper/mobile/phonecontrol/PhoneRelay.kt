package com.chuckiehelper.mobile.phonecontrol

import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Optional adapter; local MCP works without this connection. */
class PhoneRelay(private val engine: PhoneEngine, url: String, private val cookie: String) {
    private val endpoint = url.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/phone-agent"
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val worker = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(8))
    private val assistanceWorker = java.util.concurrent.ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue(8))
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    @Volatile private var closed = false
    @Volatile private var socket: WebSocket? = null
    private var attempts = 0
    init { require(endpoint.startsWith("wss://") || endpoint.startsWith("ws://")); connect() }
    private fun connect() {
        if (closed || !engine.settings.enabled) return
        state = "连接中"
        socket = client.newWebSocket(Request.Builder().url(endpoint).header("Cookie", cookie).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (closed) { ws.close(1000, "stopped"); return }
                attempts = 0
                ws.send(JSONObject().put("type", "hello").put("phone_id", engine.settings.id).put("phone_token", engine.settings.pairingToken)
                    .put("device", engine.settings.description()).put("capabilities", engine.capabilities())
                    .put("tools", PhoneToolCatalog.tools(true)).toString())
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (closed || socket !== ws) return
                val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (message.optString("type") == "ready") { state = "已连接"; return }
                if (message.optString("type") == "agent_presence") {
                    val agent = "web:" + message.optString("agent_id", "server").take(160)
                    if (message.optBoolean("connected", true)) engine.agentUi.seen(agent, message.optString("agent_name", "Agent"))
                    else engine.agentUi.disconnected(agent)
                    return
                }
                if (message.optString("type") != "invoke" || closed) return
                val executor = if (message.optString("tool") in PhoneToolCatalog.responsive) assistanceWorker else worker
                try { executor.execute {
                    if (closed || socket !== ws) return@execute
                    val id = message.optString("request_id")
                    val result = engine.invoke(message.optString("tool"), message.optJSONObject("arguments") ?: JSONObject(), id,
                        message.optBoolean("allow_advanced", false), message.optLong("deadline", 0),
                        "web:" + message.optString("agent_id", "server").take(160), message.optString("agent_name", "Agent"))
                    ws.send(JSONObject().put("type", "result").put("request_id", id).put("result", result).toString())
                } } catch (_: java.util.concurrent.RejectedExecutionException) {
                    ws.send(JSONObject().put("type", "result").put("request_id", message.optString("request_id"))
                        .put("result", PhoneEngine.errorResult(PhoneToolError("device_busy", "手机操作队列已满"))).toString())
                }
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { retry(ws, t.message ?: "连接失败") }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) { ws.close(code, reason) }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { retry(ws, reason) }
        })
    }
    private fun retry(ws: WebSocket, message: String) {
        if (closed || socket !== ws) return
        engine.agentUi.webDisconnected()
        state = "离线：$message"
        scheduler.schedule({ connect() }, minOf(30L, 1L shl minOf(attempts++, 5)), TimeUnit.SECONDS)
    }
    fun close() { closed = true; engine.agentUi.webDisconnected(); socket?.close(1000, "stopped"); scheduler.shutdownNow(); worker.shutdown(); assistanceWorker.shutdown(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); state = "未连接" }
    companion object { @Volatile var state = "未连接"; private set }
}
