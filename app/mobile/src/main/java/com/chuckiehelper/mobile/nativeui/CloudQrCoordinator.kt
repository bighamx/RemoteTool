package com.chuckiehelper.mobile.nativeui

import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.IOException

internal class CloudQrRequest(val path: String, val json: JSONObject? = null,
    val form: Map<String,String>? = null, val accessToken: String? = null) {
    override fun toString() = "CloudQrRequest(redacted)"
}
internal fun interface CloudQrTransport { suspend fun send(request: CloudQrRequest): JSONObject }

/** One active flow, one poller and one decision. Credentials live only in memory. */
internal class CloudQrCoordinator(
    private val scope: CoroutineScope,
    private val clientId: String,
    private val requestedScope: String,
    loginEnabled: Boolean,
    approvalEnabled: Boolean,
    private val transport: CloudQrTransport,
    private val scannerIdentity: suspend () -> CloudQrIdentity,
    private val currentSubject: () -> String?,
    private val completeLogin: suspend (JSONObject, String, String, () -> Boolean) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    var loginEnabled by mutableStateOf(loginEnabled); private set
    var approvalEnabled by mutableStateOf(approvalEnabled); private set
    var busy by mutableStateOf(false); private set
    var waiting by mutableStateOf<CloudQrPending?>(null); private set
    var approval by mutableStateOf<CloudQrApproval?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var needsReauthorization by mutableStateOf(false); private set
    private var generation = 0L
    private var job: Job? = null
    private val cleanup = CoroutineScope(SupervisorJob()+Dispatchers.IO)

    private fun cancelRequest(pending: CloudQrPending): Job = cleanup.launch {
        runCatching { withTimeout(5000) { transport.send(CloudQrRequest(CloudQrProtocol.API+"/cancel",json = obj(
            "client_id" to clientId,"qr_ticket" to pending.ticket,"code_verifier" to pending.verifier))) } }
    }
    fun cancel(): Job? {
        val pending = waiting
        generation++; job?.cancel(); job = null
        waiting = null; approval = null; busy = false
        error = null; notice = null; needsReauthorization = false
        return pending?.let(::cancelRequest)
    }
    fun close() {
        val last = cancel()
        if (last == null) cleanup.cancel() else last.invokeOnCompletion { cleanup.cancel() }
    }
    private fun launch(action: suspend (Long) -> Unit) {
        cancel(); error = null; notice = null; needsReauthorization = false
        val epoch = generation
        job = scope.launch {
            busy = true
            try { action(epoch) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == generation) showError(failure) }
            finally {
                if (epoch == generation) {
                    waiting?.let(::cancelRequest)
                    waiting = null; busy = false
                }
            }
        }
    }
    private fun showError(failure: Exception) {
        val http = failure as? CloudHttpFailure
        val code = http?.value?.optString("error").orEmpty()
        if (http?.status in setOf(404,503) && code !in setOf("qr_not_found") || code == "unsupported_grant_type") {
            loginEnabled = false; approvalEnabled = false
            error = "扫码服务尚未开放，请使用浏览器登录"; return
        }
        if (http?.status in setOf(401,403)) needsReauthorization = true
        error = when (code) {
            "access_denied", "qr_cancelled" -> "登录已拒绝或取消"
            "expired_token", "qr_expired" -> "二维码已过期，请重新生成"
            "qr_already_claimed" -> "此二维码已被领取，请在网页重新生成"
            "qr_already_decided", "qr_already_consumed" -> "此请求已处理，请重新扫码"
            "invalid_client", "unauthorized_client", "invalid_scope", "permission_denied" -> "当前客户端尚未获得扫码权限，请重新授权或使用浏览器登录"
            "invalid_token" -> "登录已失效，请重新授权"
            "qr_not_found" -> "二维码不存在，请重新生成"
            "invalid_grant" -> "登录凭据已失效，请重新扫码"
            else -> when {
                http?.status == 429 -> "请求过于频繁，请稍后重试"
                failure is IllegalArgumentException -> failure.message ?: "二维码响应无效"
                else -> "未能确认扫码结果，请重新扫码；不会自动重试授权"
            }
        }
    }
    fun claim(raw: String) {
        if (!loginEnabled) { error = "扫码登录尚未开放"; return }
        launch { epoch ->
            val code = CloudQrProtocol.parse(raw,"offer")
            startLogin("offer",code.code,epoch)
        }
    }
    fun create() {
        if (!loginEnabled) { error = "二维码登录尚未开放"; return }
        launch { epoch -> startLogin("receive",null,epoch) }
    }
    private suspend fun startLogin(flow: String, scanCode: String?, epoch: Long) {
        val verifier = CloudQrProtocol.secret(); val nonce = CloudQrProtocol.secret()
        val body = obj("client_id" to clientId,"scope" to requestedScope,"code_challenge" to CloudQrProtocol.challenge(verifier),
            "code_challenge_method" to "S256","nonce" to nonce)
        scanCode?.let { body.put("scan_code",it) }
        val packet = transport.send(CloudQrRequest(CloudQrProtocol.API+if (flow == "offer") "/claim" else "/requests",json=body))
        val pending = CloudQrPending.parse(packet,flow,verifier,nonce,clock())
        if (epoch != generation) { cancelRequest(pending); return }
        waiting = pending; busy = false
        var interval = pending.intervalMillis
        var throttled = interval > 3000
        var nextDelay = if (pending.waitSeconds == 0 || throttled) interval else 0L
        while (epoch == generation) {
            require(clock() < pending.expiresAt) { "二维码已过期，请重新生成" }
            if (nextDelay > 0) pause(minOf(nextDelay,pending.expiresAt-clock()))
            require(clock() < pending.expiresAt) { "二维码已过期，请重新生成" }
            val started = clock()
            val token = try {
                withTimeout((pending.expiresAt-clock()).coerceAtMost(60_000).coerceAtLeast(1)) {
                    transport.send(CloudQrRequest("/connect/token",form=mapOf("grant_type" to CloudQrProtocol.GRANT,
                        "client_id" to clientId,"qr_ticket" to pending.ticket,"code_verifier" to verifier,
                        "wait_seconds" to pending.waitSeconds.toString())))
                }
            } catch (failure: CloudHttpFailure) {
                val code = failure.value?.optString("error")
                when {
                    failure.status == 400 && code == "authorization_pending" -> {
                        // A normal long poll already covers the base interval. Legacy servers
                        // returning immediately still get a delay, so this cannot spin.
                        nextDelay = if (pending.waitSeconds == 0 || throttled) interval
                            else (interval - (clock()-started).coerceAtLeast(0)).coerceAtLeast(0)
                        continue
                    }
                    failure.status == 400 && code == "slow_down" -> {
                        interval = (interval+5000).coerceAtMost(60_000)
                        throttled = true; nextDelay = interval; continue
                    }
                    failure.status == 429 -> { nextDelay = maxOf(interval,failure.retryAfterMillis ?: 5000); continue }
                    else -> throw failure
                }
            } catch (expired: TimeoutCancellationException) {
                throw IOException("扫码结果未确认",expired)
            }
            // A successful token response consumes the ticket, even if later identity checks fail.
            if (epoch != generation) { discardTokens(token); return }
            waiting = null
            try { completeLogin(token,nonce,requestedScope) { epoch == generation } }
            catch (failure: Exception) { discardTokens(token); throw failure }
            if (epoch == generation) notice = "登录身份已核验"
            return
        }
    }
    private fun discardTokens(token: JSONObject) { cleanup.launch {
        for (name in listOf("access_token","refresh_token")) token.optString(name).takeIf { it.isNotBlank() }?.let { value ->
            runCatching { withTimeout(5000) { transport.send(CloudQrRequest("/connect/revoke",form=mapOf("client_id" to clientId,"token" to value))) } }
        }
    } }
    fun inspect(raw: String) {
        if (!approvalEnabled) { error = "扫码授权尚未开放"; return }
        launch { epoch ->
            val code = CloudQrProtocol.parse(raw,"receive")
            val owner = scannerIdentity()
            val packet = transport.send(CloudQrRequest(CloudQrProtocol.API+"/inspect",json=obj("scan_code" to code.code),accessToken=owner.accessToken))
            if (epoch != generation) return@launch
            require(currentSubject() == owner.subject) { "账户已切换，请重新扫码" }
            approval = CloudQrApproval.parse(packet,code.code,owner,clock())
        }
    }
    fun dismissApproval() { cancel(); approval = null }
    fun decide(allow: Boolean) {
        if (busy) return
        val pending = approval ?: return
        val epoch = generation
        busy = true; error = null
        job = scope.launch {
            try {
                require(clock() < pending.expiresAt) { "二维码已过期，请重新生成" }
                val owner = scannerIdentity()
                require(owner.subject == pending.account && currentSubject() == pending.account) { "账户已切换，请重新扫码" }
                val result = transport.send(CloudQrRequest(CloudQrProtocol.API+"/decide",json=obj("scan_code" to pending.scanCode,
                    "confirmation_code" to pending.confirmationCode,"decision" to if (allow) "allow" else "deny"),accessToken=owner.accessToken))
                require(result.getString("request_id") == pending.requestId && result.getString("status") == if (allow) "approved" else "denied") { "授权响应无效" }
                if (epoch == generation) notice = if (allow) "已允许此设备登录" else "已拒绝登录"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == generation) showError(failure) }
            finally { if (epoch == generation) { approval = null; busy = false } }
        }
    }
}
