package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.os.SystemClock
import android.webkit.CookieManager
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class Device(val id: String, val name: String, val endpoints: List<String>)

data class Endpoint(val url: String, val ms: Long)

data class ChannelCheck(
    val url: String,
    val ms: Long? = null,
    val error: String? = null,
    val checking: Boolean = true,
) {
    val reachable: Boolean
        get() = !checking && ms != null && error == null
}

data class Session(val device: Device, val channels: List<Endpoint>, val api: NativeApi)

fun obj(vararg fields: Pair<String, Any?>) =
    JSONObject().apply { fields.forEach { put(it.first, it.second) } }

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONObject.array(key: String) = optJSONArray(key) ?: JSONArray()

fun q(value: String) = URLEncoder.encode(value, "UTF-8")

fun bytes(value: Double): String {
    var n = value
    var i = 0
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    while (n >= 1024 && i < 4) {
        n /= 1024
        i++
    }
    return if (i == 0) "${n.toLong()} B" else "%.1f %s".format(n, units[i])
}

fun displayTime(value: String, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    if (value.isBlank() || value == "null") return "暂无"
    return runCatching {
            val instant =
                runCatching { java.time.OffsetDateTime.parse(value).toInstant() }
                    .getOrElse {
                        java.time.LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC)
                    }
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .format(instant.atZone(zone))
        }
        .getOrDefault(value)
}

class LoginRequired : IOException("登录已过期，请重新登录")

class NativeApi(
    val base: String,
    private val probing: Boolean = false,
    private val noRetry: Boolean = false,
    private val onReadSuccess: (() -> Unit)? = null,
    private val authentication: DeviceAuthSession? = null,
) {
    companion object {
        val client =
            OkHttpClient.Builder()
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }

    fun cookie(): String = if (authentication != null) authentication.token?.takeIf { it.isNotBlank() }?.let { "access_token=$it" }.orEmpty()
        else CookieManager.getInstance().getCookie(base) ?: ""

    fun withReadPolicy(noRetry: Boolean, onReadSuccess: (() -> Unit)? = null) =
        NativeApi(base, noRetry = noRetry, onReadSuccess = onReadSuccess, authentication = authentication)

    fun request(path: String, body: JSONObject? = null): Request =
        Request.Builder()
            .url(base + path)
            .apply {
                if (!probing) header("Cookie", cookie())
                if (body != null)
                    post(
                        body
                            .toString()
                            .toRequestBody("application/json; charset=utf-8".toMediaType())
                    )
            }
            .build()

    suspend fun response(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call =
            (if (probing)
                    client
                        .newBuilder()
                        .callTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .build()
                else if (noRetry || request.method !in setOf("GET", "HEAD")) client.newBuilder().retryOnConnectionFailure(false).build()
                else client)
                .newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            }
        )
    }

    suspend fun json(path: String, body: JSONObject? = null): JSONObject {
        return json(request(path, body))
    }

    suspend fun json(request: Request): JSONObject {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = try { jsonOnce(request, fresh = attempt > 0) }
            catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                if (mayRetryRead(request.method, probing, attempt, error)) {
                    android.util.Log.i("ChuckieNetwork", "${request.method} ${request.url.encodedPath}: retry read on fresh connection (${error.javaClass.simpleName})")
                    attempt++; delay(250); continue
                }
                android.util.Log.w("ChuckieNetwork", "${request.method} ${request.url.encodedPath}: ${error.javaClass.simpleName}")
                if (request.method in setOf("GET", "HEAD") && !probing && error !is LoginRequired &&
                    (error !is ApiRequestFailure || error.status in setOf(502, 503, 504))) throw ReadConnectionFailure(error)
                throw error
            }
            for (cookie in result.second) withContext(Dispatchers.Main) {
                CookieManager.getInstance().setCookie(base, cookie)
                CookieManager.getInstance().flush()
            }
            if (request.method in setOf("GET", "HEAD")) withContext(Dispatchers.Main) { onReadSuccess?.invoke() }
            return result.first
        }
    }

    private suspend fun jsonOnce(request: Request, fresh: Boolean): Pair<JSONObject, List<String>> = suspendCancellableCoroutine { cont ->
        val builder = client.newBuilder().retryOnConnectionFailure(false)
        if (probing) builder.callTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (fresh) builder.connectionPool(ConnectionPool(0, 1, java.util.concurrent.TimeUnit.SECONDS))
        val call = builder.build().newCall(request)
        // Keep cancellation wired through headers, body reading and JSON parsing.
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    try {
                        if (res.code == 401) throw LoginRequired()
                        val raw = res.body?.string().orEmpty()
                        val value = runCatching { JSONObject(raw) }.getOrNull()
                        if (!res.isSuccessful) throw ApiRequestFailure(value?.optString("message")?.takeIf { it.isNotBlank() } ?: "请求失败 HTTP ${res.code}", res.code)
                        if (value == null) throw ApiRequestFailure("服务返回了非 JSON 响应", res.code)
                        if (value.has("success") && !value.optBoolean("success")) throw ApiRequestFailure(value.optString("message", "操作失败"), res.code)
                        if (value.optJSONObject("data")?.optInt("fail", 0)?.let { it > 0 } == true) throw ApiRequestFailure(value.optString("message", "部分项目操作失败"), res.code)
                        if (cont.isActive) cont.resume(value to res.headers.values("Set-Cookie"))
                    } catch (error: Exception) { if (cont.isActive) cont.resumeWithException(error) }
                }
            }
        })
    }

    suspend fun login(username: String, password: String): String {
        val result = json("/api/auth/login", obj("username" to username, "password" to password))
        val token = result.getString("token")
        installToken(token)
        return token
    }

    suspend fun installToken(token: String) {
        authentication?.token = token
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Unit> { c ->
                CookieManager.getInstance().setCookie(
                    base,
                    "access_token=$token; Path=/; HttpOnly; SameSite=Strict" +
                        (if (base.startsWith("https:")) "; Secure" else ""),
                ) {
                    CookieManager.getInstance().flush()
                    if (c.isActive) c.resume(Unit)
                }
            }
        }
    }

    suspend fun stream(path: String, body: JSONObject, onChunk: (String) -> Unit) {
        response(request(path, body)).use { res ->
            if (!res.isSuccessful) throw IOException("命令失败 HTTP ${res.code}")
            withContext(Dispatchers.IO) {
                res.body!!.charStream().use { reader ->
                    val buffer = CharArray(2048)
                    while (currentCoroutineContext().isActive) {
                        val n = reader.read(buffer)
                        if (n < 0) break
                        withContext(Dispatchers.Main) { onChunk(String(buffer, 0, n)) }
                    }
                }
            }
        }
    }
}

class NativeModel(application: Application) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("MainActivity", 0)
    private val credentials = DeviceCredentialStore(application)
    private val authenticationSessions = mutableMapOf<String, DeviceAuthSession>()
    private var connectingDeviceId: String? = null
    var devices by mutableStateOf(readDevices())
        private set

    var session by mutableStateOf<Session?>(null)
        private set

    var browsingDevices by mutableStateOf(false)
        private set

    fun showDevices() {
        browsingDevices = true
    }

    fun returnToDevice() {
        browsingDevices = false
    }

    var connecting by mutableStateOf(false)
        private set

    var channelDevice by mutableStateOf<Device?>(null)
        private set

    var channelChecks by mutableStateOf<List<ChannelCheck>>(emptyList())
        private set

    var checkingChannels by mutableStateOf(false)
        private set

    var loginNeeded by mutableStateOf(false)
    var message by mutableStateOf<String?>(null)
    private var connectJob: Job? = null
    private var connectionEpoch = 0

    init {
        val lastId = prefs.getString("last_device", null)
        val last = devices.find { it.id == lastId }
            ?: devices.singleOrNull()?.takeIf { prefs.contains("channel_${it.id}") }
        if (last != null) connect(last)
    }

    private fun readDevices() =
        runCatching {
                JSONArray(prefs.getString("devices", "[]")).objects().map { d ->
                    Device(
                        d.getString("id"),
                        d.optString("name", "电脑"),
                        (0 until d.array("endpoints").length()).map {
                            d.array("endpoints").getString(it)
                        },
                    )
                }
            }
            .getOrDefault(emptyList())

    private fun save() {
        prefs
            .edit()
            .putString(
                "devices",
                JSONArray()
                    .apply {
                        devices.forEach {
                            put(
                                obj(
                                    "id" to it.id,
                                    "name" to it.name,
                                    "endpoints" to JSONArray(it.endpoints),
                                )
                            )
                        }
                    }
                    .toString(),
            )
            .apply()
    }

    fun forget(device: Device) {
        credentials.remove(device.id)
        authenticationSessions.remove(device.id)?.token = null
        device.endpoints.forEach { CookieManager.getInstance().setCookie(it, "access_token=; Max-Age=0; Path=/") }
        CookieManager.getInstance().flush()
        devices = devices.filter { it.id != device.id }
        save()
        if (session?.device?.id == device.id || connectingDeviceId == device.id) disconnect()
    }

    fun removeEndpoint(device: Device, url: String) {
        devices =
            devices.map { if (it.id == device.id) it.copy(endpoints = it.endpoints - url) else it }
        save()
    }

    fun rename(device: Device, name: String) {
        devices =
            devices.map {
                if (it.id == device.id) it.copy(name = name.trim().ifEmpty { it.name }) else it
            }
        save()
    }

    fun disconnect() {
        connectionEpoch++
        connectJob?.cancel()
        connecting = false
        connectingDeviceId = null
        session = null
        browsingDevices = false
        loginNeeded = false
        channelDevice = null
        channelChecks = emptyList()
        checkingChannels = false
    }

    fun connect(device: Device) {
        val current = devices.find { it.id == device.id } ?: device
        val url = preferredChannel(current)?.takeIf { it in current.endpoints }
            ?: current.endpoints.firstOrNull() ?: return
        channelDevice = null
        checkingChannels = false
        channelChecks = emptyList()
        startConnection(current, url)
    }

    fun openChannels(device: Device) {
        channelDevice = devices.find { it.id == device.id } ?: device
        refreshChannels()
    }

    fun closeChannels() {
        connectionEpoch++
        connectJob?.cancel()
        connecting = false
        checkingChannels = false
        channelDevice = null
    }

    fun preferredChannel(device: Device): String? =
        session?.takeIf { it.device.id == device.id }?.api?.base
            ?: prefs.getString("channel_${device.id}", null)

    fun refreshChannels() {
        val device = channelDevice ?: return
        val epoch = ++connectionEpoch
        connectJob?.cancel()
        connecting = false
        checkingChannels = true
        channelChecks = device.endpoints.map { ChannelCheck(it) }
        connectJob =
            viewModelScope.launch {
                try {
                    coroutineScope {
                        device.endpoints
                            .map { url ->
                                async {
                                    val result =
                                        try {
                                            val (id, endpoint) = probe(url)
                                            if (id != device.id)
                                                ChannelCheck(
                                                    url,
                                                    error = "设备标识不匹配，属于另一台电脑",
                                                    checking = false,
                                                )
                                            else ChannelCheck(url, endpoint.ms, checking = false)
                                        } catch (e: CancellationException) {
                                            throw e
                                        } catch (e: Exception) {
                                            ChannelCheck(
                                                url,
                                                error = channelError(e),
                                                checking = false,
                                            )
                                        }
                                    if (epoch == connectionEpoch)
                                        channelChecks =
                                            channelChecks.map { if (it.url == url) result else it }
                                }
                            }
                            .awaitAll()
                    }
                } finally {
                    if (epoch == connectionEpoch) checkingChannels = false
                }
            }
    }

    fun selectChannel(url: String) {
        val device = channelDevice ?: return
        if (checkingChannels || connecting || channelChecks.none { it.url == url && it.reachable })
            return
        if (session?.device?.id == device.id && session?.api?.base == url) {
            browsingDevices = false
            closeChannels()
            return
        }
        startConnection(device, url)
    }

    private fun startConnection(device: Device, url: String) {
        val epoch = ++connectionEpoch
        connectJob?.cancel()
        connecting = true
        connectingDeviceId = device.id
        connectJob =
            viewModelScope.launch {
                try {
                    // Verify identity again before sharing this device's authentication token.
                    val (id, selected) = probe(url)
                    if (id != device.id) throw IOException("设备标识不匹配，属于另一台电脑")
                    val reachable =
                        channelChecks.filter { it.reachable }.map { Endpoint(it.url, it.ms!!) }
                    currentCoroutineContext().ensureActive()
                    if (epoch != connectionEpoch) return@launch
                    val verificationAuth = DeviceAuthSession()
                    val verificationApi = NativeApi(selected.url, authentication = verificationAuth)
                    val recovered = DeviceAuthentication.recover(device.id, id,
                        read = { credentials.read(device.id) },
                        legacyTokens = {
                            (listOf(selected.url) + device.endpoints).distinct().mapNotNull { endpoint ->
                                CookieManager.getInstance().getCookie(endpoint)?.split(';')?.map(String::trim)
                                    ?.firstOrNull { it.startsWith("access_token=") }?.substringAfter('=')
                            }
                        },
                        validate = { token ->
                            verificationAuth.token = token
                            try { verificationApi.json("/api/auth/me"); true } catch (_: LoginRequired) { false }
                        },
                        signIn = { login ->
                            try { verificationApi.login(login.username, login.password) } catch (_: LoginRequired) { null }
                        },
                        save = { credentials.save(device.id, it) },
                    )
                    currentCoroutineContext().ensureActive()
                    if (epoch != connectionEpoch) return@launch
                    val authentication = authenticationSessions.getOrPut(device.id) { DeviceAuthSession() }
                    val api = NativeApi(selected.url, authentication = authentication)
                    if (recovered != null) api.installToken(recovered.token) else authentication.token = null
                    if (epoch == connectionEpoch) {
                        loginNeeded = recovered == null
                        session =
                            Session(
                                device,
                                (reachable.filter { it.url != selected.url } + selected),
                                api,
                            )
                        prefs.edit().putString("channel_${device.id}", url)
                            .putString("last_device", device.id).apply()
                        browsingDevices = false
                        channelDevice = null
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (epoch == connectionEpoch) {
                        message = channelError(e)
                        channelChecks =
                            channelChecks.map {
                                if (it.url == url)
                                    ChannelCheck(url, error = channelError(e), checking = false)
                                else it
                            }
                    }
                } finally {
                    if (epoch == connectionEpoch) { connecting = false; connectingDeviceId = null }
                }
            }
    }

    fun savedUsername(deviceId: String): String = credentials.read(deviceId)?.username.orEmpty()

    suspend fun login(target: Session, username: String, password: String) {
        val (identity, _) = probe(target.api.base)
        if (identity != target.device.id) throw IOException("设备标识不匹配，属于另一台电脑")
        val token = target.api.login(username, password)
        credentials.save(target.device.id, DeviceLogin(username, password, token))
        if (session === target) loginNeeded = false
    }

    private fun channelError(error: Exception): String =
        when (error) {
            is java.net.UnknownHostException -> "域名无法解析，请检查 DNS / VPN 设置"
            is java.net.SocketTimeoutException,
            is java.io.InterruptedIOException -> "连接超时，请检查网络或防火墙"
            is java.net.ConnectException -> "无法连接，服务或端口不可达"
            else -> error.message?.takeIf { it.isNotBlank() } ?: "检测失败"
        }

    suspend fun add(name: String, raw: String, expected: String?) {
        val uri = URI(raw.trim().trimEnd('/'))
        require(
            uri.scheme in listOf("http", "https") &&
                uri.host != null &&
                uri.userInfo == null &&
                uri.query == null &&
                uri.fragment == null &&
                uri.path.isNullOrEmpty()
        ) {
            "请输入完整的 http:// 或 https:// 根地址"
        }
        val url = uri.toString()
        val (id, _) = probe(url)
        require(expected == null || id == expected) { "此地址属于另一台电脑，不能加入当前设备" }
        val old = devices.find { it.id == id }
        devices =
            if (old == null) devices + Device(id, name.trim().ifEmpty { "新设备" }, listOf(url))
            else
                devices.map {
                    if (it.id == id) it.copy(endpoints = (it.endpoints + url).distinct()) else it
                }
        save()
    }

    private suspend fun probe(url: String): Pair<String, Endpoint> {
        val start = SystemClock.elapsedRealtime()
        val data = NativeApi(url, true).json("/api/device/identity")
        val id = data.getString("id")
        require(id.matches(Regex("[0-9a-f]{64}"))) { "设备标识格式不正确" }
        return id to Endpoint(url, SystemClock.elapsedRealtime() - start)
    }
}
