package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class CloudHttpFailure(val status: Int, val value: JSONObject?) : IOException(
    value?.optString("message")?.takeIf { it.isNotBlank() }
        ?: value?.optString("error_description")?.takeIf { it.isNotBlank() }
        ?: value?.optString("title")?.takeIf { it.isNotBlank() }
        ?: "云服务请求失败（HTTP $status）")

internal class CloudAccountModel(app: Application) : AndroidViewModel(app) {
    companion object {
        const val BASE = "https://api.xbyham.com"
        const val REDIRECT = "remotetool://cloud/login"
        const val CLIENT = "remotetool-android"
        const val SCOPE = "openid profile offline_access appdata.config"
        const val APP_ID = "remotetool"
        const val CONFIG_PATH = "/api/app-data/v1/apps/$APP_ID/config"
    }
    private val store = CloudAccountStore(app)
    private val snapshots = CloudConfiguration(app)
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    private var account by mutableStateOf(store.read("session"))
    var panelVisible by mutableStateOf(false)
    var busy by mutableStateOf(false); private set
    var waitingForLogin by mutableStateOf(store.read("pending") != null); private set
    var displayName by mutableStateOf(account?.optString("displayName").orEmpty()); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var cloudInfo by mutableStateOf<JSONObject?>(null); private set
    var downloadPreview by mutableStateOf<JSONObject?>(null); private set
    var configurationVersion by mutableIntStateOf(0); private set
    val loggedIn get() = account != null
    val subtitle get() = if (loggedIn) displayName else "同步设备与设置"
    private var revision: Long? = null

    fun open() { panelVisible = true; error = null; notice = null; if (loggedIn) refresh() }
    private fun work(action: suspend () -> Unit) {
        if (busy) return
        busy = true; error = null; notice = null
        viewModelScope.launch {
            try { action() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "云服务暂不可用，请稍后重试" }
            finally { busy = false }
        }
    }
    private fun random() = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

    fun beginLogin(context: Context) {
        if (busy) return
        error = null; notice = null
        try {
            val verifier = random()
            val state = random()
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
            store.save("pending", obj("verifier" to verifier, "state" to state, "createdAt" to System.currentTimeMillis()))
            val url = Uri.parse("$BASE/connect/authorize").buildUpon()
                .appendQueryParameter("client_id", CLIENT).appendQueryParameter("redirect_uri", REDIRECT)
                .appendQueryParameter("response_type", "code").appendQueryParameter("scope", SCOPE)
                .appendQueryParameter("code_challenge", challenge).appendQueryParameter("code_challenge_method", "S256")
                .appendQueryParameter("state", state).build()
            context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE))
            waitingForLogin = true
        } catch (failure: Exception) {
            store.remove("pending"); waitingForLogin = false
            error = failure.message ?: "无法打开浏览器登录"
        }
    }
    fun cancelLogin() { store.remove("pending"); waitingForLogin = false }

    fun receiveCallback(uri: Uri?): Boolean {
        if (uri?.scheme != "remotetool" || uri.host != "cloud" || uri.path != "/login") return false
        panelVisible = true
        if (busy) return true
        work {
            val pending = store.read("pending") ?: throw IOException("登录请求已失效，请重新登录")
            val state = uri.getQueryParameter("state").orEmpty()
            require(state.isNotBlank() && MessageDigest.isEqual(state.toByteArray(), pending.getString("state").toByteArray()) &&
                System.currentTimeMillis() - pending.getLong("createdAt") in 0..600_000L) { "登录校验失败或已超时，请重新登录" }
            store.remove("pending"); waitingForLogin = false
            if (uri.getQueryParameter("error") != null) throw IOException("网站登录未完成或授权已取消，请重新登录")
            val code = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() && it.length <= 4096 } ?: throw IOException("登录授权码缺失")
            val token = request("/connect/token", FormBody.Builder().add("grant_type", "authorization_code").add("client_id", CLIENT)
                .add("code", code).add("redirect_uri", REDIRECT).add("code_verifier", pending.getString("verifier")).build())
            require(token.getString("token_type").equals("Bearer", true)) { "云账户返回了不支持的登录凭据" }
            val access = token.getString("access_token")
            val who = request("/api/app-data/v1/me", accessToken = access)
            val saved = obj("accessToken" to access, "refreshToken" to token.optString("refresh_token"),
                "expiresAt" to System.currentTimeMillis() + token.getLong("expires_in") * 1000L,
                "subject" to who.get("userId").toString(), "displayName" to who.optString("displayName").ifBlank { who.optString("userName", "云账户") })
            store.save("session", saved)
            account = saved; displayName = saved.getString("displayName")
            revision = null; cloudInfo = null; downloadPreview = null
            notice = "已登录"
            readInfo()
        }
        return true
    }

    private suspend fun accessToken(): String {
        val saved = account ?: throw IOException("请先登录云账户")
        if (saved.getLong("expiresAt") > System.currentTimeMillis() + 60_000L) return saved.getString("accessToken")
        val refresh = saved.optString("refreshToken")
        if (refresh.isBlank()) { clearSession(); throw IOException("云账户登录已过期，请重新登录") }
        val token = try {
            request("/connect/token", FormBody.Builder().add("grant_type", "refresh_token").add("client_id", CLIENT).add("refresh_token", refresh).build())
        } catch (failure: CloudHttpFailure) {
            if (failure.status == 400 && failure.value?.optString("error") == "invalid_grant") clearSession()
            throw failure
        }
        val renewed = JSONObject(saved.toString()).put("accessToken", token.getString("access_token"))
            .put("refreshToken", token.optString("refresh_token").ifBlank { refresh })
            .put("expiresAt", System.currentTimeMillis() + token.getLong("expires_in") * 1000L)
        store.save("session", renewed); account = renewed
        return renewed.getString("accessToken")
    }

    private suspend fun api(body: JSONObject? = null): JSONObject {
        val access = accessToken()
        try {
            val result = request(CONFIG_PATH, body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType()),
                access, if (body == null) "GET" else "PUT")
            val version = result.opt("revision")
            require((version is Int || version is Long) && result.getLong("revision") >= 0 &&
                result.has("payload") && (result.isNull("payload") || result.optJSONObject("payload") != null)) { "云配置响应格式错误" }
            return result
        } catch (failure: CloudHttpFailure) {
            if (failure.status in setOf(401, 403)) { clearSession(); throw IOException("云账户授权已失效或无权访问，请重新登录") }
            if (failure.status == 409) { revision = null; cloudInfo = null; throw IOException("云配置已被另一台设备更新，请刷新后查看，再决定是否上传覆盖") }
            throw failure
        }
    }
    private suspend fun readInfo() {
        val info = api()
        cloudInfo = info
        revision = info.optLong("revision", 0)
    }
    fun refresh() = work { readInfo() }
    fun upload() = work {
        val expected = revision ?: throw IOException("请先刷新云端状态，再上传配置")
        val payload = withContext(Dispatchers.IO) { snapshots.export() }
        val result = api(obj("expectedRevision" to expected, "payload" to payload))
        cloudInfo = result; revision = result.getLong("revision")
        notice = "已上传"
    }
    fun download() = work {
        val result = api()
        cloudInfo = result; revision = result.optLong("revision", 0)
        val payload = result.optJSONObject("payload") ?: throw IOException("云端还没有配置，请先从已有配置的设备上传")
        downloadPreview = withContext(Dispatchers.IO) { snapshots.validate(payload) }
    }
    fun dismissPreview() { if (!busy) downloadPreview = null }
    fun applyDownload() = work {
        val payload = downloadPreview ?: return@work
        withContext(Dispatchers.IO) { snapshots.apply(payload) }
        downloadPreview = null; configurationVersion++
        notice = "配置已应用"
    }
    private fun clearSession() {
        store.remove("session"); account = null; displayName = ""; revision = null; cloudInfo = null; downloadPreview = null
    }
    fun logout() = work {
        val saved = account
        // Local credentials are removed even when revocation cannot reach the server.
        val refresh = saved?.optString("refreshToken").orEmpty()
        val access = saved?.optString("accessToken").orEmpty()
        clearSession(); cancelLogin()
        var revoked = true
        for (token in listOf(refresh, access).filter { it.isNotBlank() }) try {
            request("/connect/revoke", FormBody.Builder().add("client_id", CLIENT).add("token", token).build())
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { revoked = false }
        notice = if (revoked) "已退出登录" else "已退出，云端授权撤销失败，可在网页管理中撤销"
    }

    private suspend fun request(path: String, body: RequestBody? = null, accessToken: String? = null,
        method: String = if (body == null) "GET" else "POST"): JSONObject = suspendCancellableCoroutine { cont ->
        val request = Request.Builder().url(BASE + path).header("Accept", "application/json")
            .apply { if (accessToken != null) header("Authorization", "Bearer $accessToken") }.method(method, body).build()
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use { res -> try {
                    val source = res.body?.source()
                    // JSON escaping can expand a valid snapshot; the decoded payload is validated separately.
                    val max = CloudConfiguration.MAX_BYTES * 6 + 64 * 1024
                    source?.request(max.toLong() + 1)
                    if ((source?.buffer?.size ?: 0L) > max) throw IOException("云服务响应超过大小限制")
                    val raw = source?.buffer?.readByteArray() ?: ByteArray(0)
                    val value = if (raw.isEmpty()) JSONObject() else runCatching { JSONObject(String(raw, Charsets.UTF_8)) }.getOrNull()
                    if (!res.isSuccessful) throw CloudHttpFailure(res.code, value)
                    if (value == null) throw IOException("云服务响应格式错误")
                    if (cont.isActive) cont.resume(value)
                } catch (failure: Exception) { if (cont.isActive) cont.resumeWithException(failure) } }
            }
        })
    }
}
