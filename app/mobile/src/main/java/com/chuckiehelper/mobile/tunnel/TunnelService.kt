package com.chuckiehelper.mobile.tunnel

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import com.chuckiehelper.mobile.nativeui.NativeApi
import com.chuckiehelper.mobile.background.BackgroundNotification
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

object TunnelRuntime {
    internal data class Setup(val deviceId: String, val name: String, val api: NativeApi, val rules: List<TunnelRule>, val runId: String = java.util.UUID.randomUUID().toString())
    @Volatile internal var desired: Setup? = null
    private val mutable = MutableStateFlow(TunnelStatus())
    val status = mutable.asStateFlow()
    internal fun update(value: TunnelStatus) { mutable.value = value }
    fun start(context: Context, deviceId: String, name: String, api: NativeApi, rules: List<TunnelRule>) {
        require(rules.isNotEmpty() && rules.size <= 32); rules.forEach { it.validate() }
        runCatching { com.chuckiehelper.mobile.startup.StartupSettings(context).rememberTunnel(deviceId, api.base) }
        desired = Setup(deviceId, name, api, rules.toList())
        update(TunnelStatus(deviceId, name, "连接中", running = true))
        try { context.startForegroundService(Intent(context, TunnelService::class.java)) }
        catch (error: Exception) { desired = null; update(TunnelStatus(error = error.message)); throw error }
    }
    fun stop(context: Context) {
        com.chuckiehelper.mobile.startup.StartupService.pauseTunnel()
        desired = null; context.stopService(Intent(context, TunnelService::class.java))
        update(status.value.copy(running = false, active = 0, state = "已停止"))
    }
}

class TunnelService : Service() {
    private var engine: TunnelEngine? = null
    private var runId: String? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { TunnelRuntime.stop(this); return START_NOT_STICKY }
        val setup = TunnelRuntime.desired ?: run { stopSelf(); return START_NOT_STICKY }
        runId = setup.runId
        val stop = PendingIntent.getService(this, 204, Intent(this, TunnelService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            BackgroundNotification.start(this, "port-tunnel", "端口转发", "停止转发", stop)
            engine?.close(); engine = TunnelEngine(applicationContext, setup).also { it.start() }
        } catch (error: Exception) {
            TunnelRuntime.update(TunnelStatus(setup.deviceId, setup.name, error = error.message)); stopSelf()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        BackgroundNotification.finish(this, "port-tunnel")
        engine?.close(); engine = null
        if (TunnelRuntime.desired == null || TunnelRuntime.desired?.runId == runId) {
            TunnelRuntime.desired = null
            TunnelRuntime.update(TunnelRuntime.status.value.copy(running = false, active = 0, state = "已停止"))
        }
        super.onDestroy()
    }
}

internal class TunnelEngine(private val context: Context, private val setup: TunnelRuntime.Setup) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = NativeApi.client.newBuilder().pingInterval(20, TimeUnit.SECONDS).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
    @Volatile private var peer: TunnelPeer? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var closing = false
    private val generation = AtomicInteger()
    @Volatile private var failedToken: String? = null
    private val sent = AtomicLong(); private val received = AtomicLong(); private val dropped = AtomicLong()
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    @Volatile private var network = connectivity.activeNetwork
    private val wakeLock = context.getSystemService(android.os.PowerManager::class.java)
        .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "RemoteTool:PortTunnel").apply { setReferenceCounted(false) }
    private var lockRefreshed = 0L
    private var callbackRegistered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(current: Network) { if (network != current) { network = current; reconnect() } }
        override fun onLost(current: Network) { if (network == current) { network = null; reconnect() } }
    }
    private fun reconnect() { peer?.close(IOException("网络已变化")); socket?.cancel() }
    private fun publish(state: String = TunnelRuntime.status.value.state, error: String? = null) {
        if (closing) return
        TunnelRuntime.update(TunnelStatus(setup.deviceId, setup.name, state, true, peer?.active ?: 0,
            sent.get(), received.get(), dropped.get(), error))
    }
    fun start() {
        try { connectivity.registerDefaultNetworkCallback(callback); callbackRegistered = true } catch (_: Exception) { }
        scope.launch {
            var attempts = 0
            var refreshed = false
            while (isActive && !closing) {
                var failure: Throwable? = null
                try {
                    publish(if (attempts == 0) "连接中" else "正在重连")
                    val connected = connect()
                    connected.startListeners()
                    attempts = 0; refreshed = false; publish("已连接")
                    failure = connected.finished.await()
                } catch (cancelled: CancellationException) { if (!isActive) throw cancelled; failure = cancelled }
                catch (error: Exception) { failure = error }
                finally { generation.incrementAndGet(); peer?.close(); peer = null; socket?.cancel(); socket = null }
                if (closing || !isActive) break
                if (failure is AuthenticationExpired) {
                    if (!refreshed) {
                        refreshed = true
                        val token = try { withTimeout(15_000) {
                            setup.api.onUnauthorized?.invoke(failedToken)?.also { if (it.isNotBlank()) setup.api.installToken(it) }
                        } }
                            catch (cancelled: CancellationException) { if (!isActive) throw cancelled; null }
                            catch (_: Exception) { null }
                        if (!token.isNullOrBlank()) continue
                    }
                    publish("登录已过期", "请登录设备后重新开启转发")
                    TunnelRuntime.stop(context); break
                }
                publish("等待重连", failure?.message ?: "连接已断开")
                delay(minOf(30_000L, 1000L shl minOf(attempts++, 5)) + kotlin.random.Random.nextLong(0, 500))
            }
        }
        scope.launch {
            while (isActive) {
                delay(1000)
                if (peer != null) publish(error = TunnelRuntime.status.value.error)
                if ((peer?.active ?: 0) > 0) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (!wakeLock.isHeld || now - lockRefreshed > 60_000) {
                        runCatching { wakeLock.acquire(120_000); lockRefreshed = now }
                    }
                } else if (wakeLock.isHeld) runCatching { wakeLock.release() }
            }
        }
    }
    private suspend fun connect(): TunnelPeer {
        val ready = CompletableDeferred<TunnelPeer>()
        val epoch = generation.incrementAndGet()
        val url = setup.api.base.trimEnd('/').replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/tunnel"
        val cookie = setup.api.cookie()
        failedToken = cookie.split(';').map { it.trim() }.firstOrNull { it.startsWith("access_token=") }?.substringAfter('=')
        val request = Request.Builder().url(url).header("Cookie", cookie).build()
        var current: TunnelPeer? = null
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                if (closing || epoch != generation.get()) { ws.cancel(); return }
                val connected = TunnelPeer(ws, setup.rules, scope.coroutineContext, sent, received, dropped) { if (epoch == generation.get()) publish(error = it) }
                current = connected; peer = connected; connected.startReceiving()
                val hello = JSONObject().put("type", "configure").put("version", 1).put("client_id", TunnelSettings(context).clientId)
                    .put("run_id", setup.runId)
                    .put("name", android.os.Build.MODEL).put("rules", JSONArray().apply { setup.rules.forEach { put(it.json()) } })
                if (!ws.send(hello.toString())) ready.completeExceptionally(IOException("隧道配置发送失败"))
            }
            override fun onMessage(ws: WebSocket, text: String) {
                if (closing || epoch != generation.get()) return
                try {
                    val value = JSONObject(text)
                    when (value.optString("type")) {
                        "ready" -> current?.let { ready.complete(it) }
                        "error" -> { val error = IOException(value.optString("message", "隧道配置失败")); ready.completeExceptionally(error); current?.close(error) }
                        "stopped" -> { ready.completeExceptionally(IOException("电脑已停止转发")); TunnelRuntime.stop(context) }
                        "auth_expired" -> { val error = AuthenticationExpired(); ready.completeExceptionally(error); current?.close(error) }
                    }
                } catch (error: Exception) { ready.completeExceptionally(error); current?.close(error) }
            }
            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (closing || epoch != generation.get()) return
                if (bytes.size > TunnelFrame.MAX_FRAME) current?.close(IOException("隧道消息过大")) else current?.receive(bytes.toByteArray())
            }
            override fun onFailure(ws: WebSocket, error: Throwable, response: Response?) {
                if (closing || epoch != generation.get()) return
                val failure = if (response?.code == 401) AuthenticationExpired() else error
                ready.completeExceptionally(failure); current?.close(failure)
            }
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                if (closing || epoch != generation.get()) return
                val error = IOException("隧道已关闭（$code）"); ready.completeExceptionally(error); current?.close(error)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) = onClosing(ws, code, reason)
        })
        return withTimeout(20_000) { ready.await() }
    }
    fun close() {
        closing = true; generation.incrementAndGet(); scope.cancel(); peer?.close(); socket?.cancel()
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
        if (callbackRegistered) runCatching { connectivity.unregisterNetworkCallback(callback) }
        // A derived OkHttp client shares the app's dispatcher/pool; never shut those down here.
    }
    private class AuthenticationExpired : IOException("登录已过期")
}
