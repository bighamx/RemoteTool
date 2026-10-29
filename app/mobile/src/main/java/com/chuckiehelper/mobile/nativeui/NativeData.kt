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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class Device(val id: String, val name: String, val endpoints: List<String>)

data class Endpoint(val url: String, val ms: Long, val serverVersion: String? = null)

data class ChannelCheck(
    val url: String,
    val ms: Long? = null,
    val error: String? = null,
    val checking: Boolean = true,
) {
    val reachable: Boolean
        get() = !checking && ms != null && error == null
}

data class Session(val device: Device, val channels: List<Endpoint>, val api: NativeApi) {
    val serverVersion get() = channels.firstOrNull { it.url == api.base }?.serverVersion
}

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
    override val base: String,
    private val probing: Boolean = false,
    private val noRetry: Boolean = false,
    private val onReadSuccess: (() -> Unit)? = null,
    private val authentication: DeviceAuthSession? = null,
    val hermesProfile: String = "default",
) : com.chuckiehelper.mobile.phonecontrol.PhoneWebApi {
    /** Called once when a request comes back 401: return a fresh token (or null).
     *  Set by the model on the session's API instance; copied by [withReadPolicy]. */
    var onUnauthorized: (suspend (failedToken: String?) -> String?)? = null

    companion object {
        val client =
            OkHttpClient.Builder()
                .addInterceptor(JsonRequestCompression())
                .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }

    override fun cookie(): String = if (authentication != null) authentication.token?.takeIf { it.isNotBlank() }?.let { "access_token=$it" }.orEmpty()
        else CookieManager.getInstance().getCookie(base) ?: ""

    fun withReadPolicy(noRetry: Boolean, onReadSuccess: (() -> Unit)? = null) =
        NativeApi(base, noRetry = noRetry, onReadSuccess = onReadSuccess, authentication = authentication, hermesProfile = hermesProfile)
            .also { it.onUnauthorized = onUnauthorized }

    fun withHermesProfile(profile: String) =
        NativeApi(base, probing, noRetry, onReadSuccess, authentication, profile)
            .also { it.onUnauthorized = onUnauthorized }

    private fun scopedPath(path: String): String =
        if (hermesProfile != "default" && path.startsWith("/api/hermes/") && !path.startsWith("/api/hermes/profiles"))
            "/api/hermes/profiles/${android.net.Uri.encode(hermesProfile)}/" + path.removePrefix("/api/hermes/") else path

    override fun request(path: String, body: JSONObject?): Request =
        Request.Builder()
            .url(base + scopedPath(path))
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
                    cont.resume(response) { _, undelivered, _ -> undelivered.close() }
                }
            }
        )
    }

    suspend fun <T> streamingResponse(request: Request, action: suspend (Response) -> T): T {
        val call = client.newBuilder().retryOnConnectionFailure(false).build().newCall(request)
        return withStreamingCallCancellation(call) {
            val response = suspendCancellableCoroutine<Response> { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) { if (cont.isActive) cont.resumeWithException(error) }
                    override fun onResponse(call: Call, response: Response) {
                        cont.resume(response) { _, undelivered, _ -> undelivered.close() }
                    }
                })
            }
            response.use { action(it) }
        }
    }

    override suspend fun json(path: String, body: JSONObject?): JSONObject {
        return json(request(path, body))
    }

    override suspend fun json(request: Request): JSONObject {
        var attempt = 0
        var current = request
        var healed = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val result = try { jsonOnce(current, fresh = attempt > 0) }
            catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                // A 401 almost always means the 24h token expired. Re-sign in with the
                // credentials the device already stored and replay the request once,
                // rather than surfacing "登录已过期" and forcing a manual reconnect.
                // The cookie is baked into the Request, so it must be rebuilt from the
                // refreshed session before retrying.
                if (error is LoginRequired && !healed && onUnauthorized != null && current.canRefreshAuthentication()) {
                    healed = true
                    val failed = current.authenticationToken()
                    val token = try { onUnauthorized?.invoke(failed) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    if (!token.isNullOrBlank()) {
                        installToken(token)
                        current = current.newBuilder().header("Cookie", cookie()).build()
                        continue
                    }
                }
                if (mayRetryRead(request.method, probing, attempt, error, noRetry)) {
                    android.util.Log.i("ChuckieNetwork", "${request.method} ${request.url.encodedPath}: retry read on fresh connection (${error.javaClass.simpleName})")
                    attempt++; delay(250); continue
                }
                android.util.Log.w("ChuckieNetwork", "${request.method} ${request.url.encodedPath}: ${error.javaClass.simpleName}")
                if (request.method in setOf("GET", "HEAD") && !probing && error !is LoginRequired &&
                    (error !is ApiRequestFailure || error.status in setOf(502, 503, 504))) throw wrapReadFailure(error)
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
                        if (!res.isSuccessful) throw ApiRequestFailure(value?.optString("message")?.takeIf { it.isNotBlank() } ?: "请求失败 HTTP ${res.code}", res.code,
                            value?.optString("code").orEmpty(), value?.optString("delivery").orEmpty())
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
        streamingResponse(request(path, body)) { res ->
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

    var configurationVersion by mutableIntStateOf(0)
        private set

    fun reloadCloudConfiguration() {
        devices = readDevices()
        session = session?.let { current ->
            // Imported credentials have no portable token. Drop a previous in-memory
            // session token too, so the next request signs in with the updated login.
            if (credentials.read(current.device.id)?.token?.isEmpty() == true)
                authenticationSessions[current.device.id]?.token = null
            current.copy(device = devices.find { it.id == current.device.id } ?: current.device)
        }
        configurationVersion++
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
    private val refreshLock = Mutex()

    /**
     * Re-sign in silently with the credentials already stored for this device and return a
     * fresh token. Called from any request that comes back 401, so a session that outlives
     * its 24h token heals itself instead of showing "登录已过期" and forcing a reconnect.
     * The mutex + failed-token check keep a burst of concurrent 401s to a single login.
     */
    private fun attachRefresher(device: Device, api: NativeApi, authentication: DeviceAuthSession) {
        api.onUnauthorized = { failedToken ->
            refreshLock.withLock {
                val token = DeviceAuthentication.refresh(
                    failedToken = failedToken,
                    currentToken = authentication.token,
                    read = { credentials.read(device.id) },
                    signIn = { saved ->
                        try { api.login(saved.username, saved.password) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { null }
                    },
                    save = { credentials.save(device.id, it) },
                )
                if (!token.isNullOrBlank()) loginNeeded = false
                token
            }
        }
    }
    private var connectJob: Job? = null
    private var connectionEpoch = 0

    init {
        val lastId = prefs.getString("last_device", null)
        val last = devices.find { it.id == lastId }
            ?: devices.singleOrNull()?.takeIf { !prefs.getBoolean("cloud_requires_connection_selection", false) && prefs.contains("channel_${it.id}") }
        if (last != null) connect(last)
        else if (com.chuckiehelper.mobile.BuildConfig.PHONE_COMPANION && devices.isEmpty()) {
            // Preserve the first standalone version's saved address when moving to
            // the shared device UI. Probe anonymously before creating any login session.
            val legacy = application.getSharedPreferences("connection", 0).getString("address", "").orEmpty()
            if (legacy.isNotBlank()) viewModelScope.launch {
                try {
                    add("电脑", legacy, null)
                    devices.firstOrNull { legacy.trimEnd('/') in it.endpoints }?.let(::connect)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { message = "原连接暂不可用，请在设备页面重试" }
            }
        }
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

    suspend fun refreshServerVersion() {
        val current = session ?: return
        try {
            val identity = current.api.json("/api/device/identity")
            if (session?.api === current.api && identity.optString("id") == current.device.id) {
                val latest = session ?: return
                session = latest.copy(channels = latest.channels.map {
                    if (it.url == current.api.base) it.copy(serverVersion = identity.serverVersionDescription()) else it
                })
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* A version read must not interrupt an existing connection. */ }
    }

    fun rename(device: Device, name: String) {
        devices =
            devices.map {
                if (it.id == device.id) it.copy(name = name.trim().ifEmpty { it.name }) else it
            }
        save()
    }

    suspend fun editEndpoint(device: Device, oldUrl: String, raw: String) {
        val url = normalizedDeviceAddress(raw)
        val (id, _) = probe(url)
        require(id == device.id) { "此地址属于另一台电脑，不能替换当前设备地址" }
        val current = devices.find { it.id == device.id } ?: error("设备已被移除")
        val updated = current.copy(endpoints = replacedDeviceAddresses(current.endpoints, oldUrl, url))
        devices = devices.map { if (it.id == id) updated else it }
        if (prefs.getString("channel_$id", null) == oldUrl) prefs.edit().putString("channel_$id", url).apply()
        channelDevice = channelDevice?.let { if (it.id == id) updated else it }
        channelChecks = channelChecks.filter { it.url != oldUrl }
        save()
        val active = session?.takeIf { it.device.id == id }
        if (active != null) {
            session = active.copy(device = updated)
            if (active.api.base == oldUrl && oldUrl != url) startConnection(updated, url)
        }
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
        startConnection(current, url, allowFallback = true)
    }

    fun openChannels(device: Device) {
        channelDevice = devices.find { it.id == device.id } ?: device
        refreshChannels()
    }

    fun closeChannels() {
        connectionEpoch++
        connectJob?.cancel()
        connecting = false
        connectingDeviceId = null
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
        connectingDeviceId = null
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
        if (session?.device?.id == device.id && session?.api?.base == url && !loginNeeded) {
            browsingDevices = false
            closeChannels()
            return
        }
        startConnection(device, url)
    }

    private fun startConnection(device: Device, url: String, allowFallback: Boolean = false) {
        val epoch = ++connectionEpoch
        connectJob?.cancel()
        connecting = true
        connectingDeviceId = device.id
        connectJob =
            viewModelScope.launch {
                try {
                    // Verify identity again before sharing this device's authentication token.
                    val candidates = if (allowFallback) deviceAddressCandidates(device.endpoints, url) else listOf(url)
                    val (id, selected) = selectVerifiedDeviceAddress(candidates, device.id, ::probe)
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
                    attachRefresher(device, api, authentication)
                    if (recovered != null) api.installToken(recovered.token) else authentication.token = null
                    if (epoch == connectionEpoch) {
                        loginNeeded = recovered == null
                        session =
                            Session(
                                device,
                                (reachable.filter { it.url != selected.url } + selected),
                                api,
                            )
                        prefs.edit().putString("channel_${device.id}", selected.url)
                            .putString("last_device", device.id).remove("cloud_requires_connection_selection").apply()
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

    fun savedUsername(deviceId: String): String = credentials.read(deviceId)?.username
        ?: if (com.chuckiehelper.mobile.BuildConfig.PHONE_COMPANION) {
            val legacy = getApplication<Application>().getSharedPreferences("connection", 0)
            legacy.getString("username", "").orEmpty().takeIf {
                legacy.getString("address", "").orEmpty().trimEnd('/') in devices.firstOrNull { row -> row.id == deviceId }?.endpoints.orEmpty()
            }.orEmpty()
        } else ""

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
        val url = normalizedDeviceAddress(raw)
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
        return id to Endpoint(url, SystemClock.elapsedRealtime() - start, data.serverVersionDescription())
    }
}
