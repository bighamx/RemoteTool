package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

internal object CloudQrProtocol {
    const val BASE = "https://api.xbyham.com"
    const val API = "/api/qr-login/v1"
    const val GRANT = "urn:wxbackend:params:oauth:grant-type:qr-login"
    const val APPROVAL_SCOPE = "qrlogin.approve"
    private val random = SecureRandom()
    fun secret(): String = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    fun validSecret(value: String): Boolean = value.matches(Regex("[A-Za-z0-9_-]{43}")) && runCatching {
        val bytes = Base64.getUrlDecoder().decode(value)
        bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value
    }.getOrDefault(false)
    fun trustedPath(url: String): String {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.host == "api.xbyham.com" && uri.port in setOf(-1,443) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.rawPath.startsWith('/')) { "云服务端点不可信" }
        return uri.rawPath
    }
    fun parse(raw: String, expectedFlow: String): CloudQrCode {
        require(raw.length in 1..1024) { "二维码内容无效" }
        val uri = runCatching { URI(raw) }.getOrElse { throw IllegalArgumentException("二维码内容无效") }
        require(uri.scheme == "https" && uri.host == "api.xbyham.com" && uri.port in setOf(-1,443) &&
            uri.rawUserInfo == null && uri.rawQuery == null && uri.rawPath == "/QrLogin/Scan") { "请扫描 api.xbyham.com 的登录二维码" }
        val fields = linkedMapOf<String,String>()
        for (part in uri.rawFragment.orEmpty().split('&')) {
            val pair = part.split('=',limit=2)
            require(pair.size == 2) { "二维码参数无效" }
            val key = runCatching { URLDecoder.decode(pair[0], "UTF-8") }.getOrElse { throw IllegalArgumentException("二维码编码无效") }
            val value = runCatching { URLDecoder.decode(pair[1], "UTF-8") }.getOrElse { throw IllegalArgumentException("二维码编码无效") }
            require(fields.put(key,value) == null) { "二维码参数重复" }
        }
        require(fields.keys == setOf("v","flow","code") && fields["v"] == "1" && fields["flow"] in setOf("offer","receive") && validSecret(fields["code"].orEmpty())) { "二维码版本或参数无效" }
        require(fields["flow"] == expectedFlow) { if (expectedFlow == "offer") "此码用于授权其他设备，请使用已登录账户的扫一扫授权登录" else "此码用于登录扫码 App，请使用登录页的扫码登录" }
        return CloudQrCode(expectedFlow,fields.getValue("code"))
    }
    fun expiry(packet: JSONObject, now: Long): Long {
        val seconds = packet.getLong("expires_in")
        require(seconds in 1..180) { "二维码已过期或有效期无效" }
        val expires = Instant.parse(packet.getString("expires_at")).toEpochMilli()
        return minOf(expires,now+seconds*1000).also { require(it > now) { "二维码已过期" } }
    }
}

internal class CloudQrCode(val flow: String, val code: String) {
    override fun toString() = "CloudQrCode($flow, redacted)"
}
internal class CloudQrPending(val requestId: String, val flow: String, val ticket: String, val verifier: String,
    val nonce: String, val confirmationCode: String, val expiresAt: Long, val intervalMillis: Long, val payload: String?,
    val waitSeconds: Int = 20) {
    override fun toString() = "CloudQrPending(redacted)"
    companion object {
        fun parse(packet: JSONObject, flow: String, verifier: String, nonce: String, now: Long): CloudQrPending {
            require(packet.getString("flow") == flow && packet.getString("status") == if (flow == "offer") "claimed" else "pending") { "扫码响应状态无效" }
            val id = packet.getString("request_id"); val code = packet.getString("confirmation_code"); val ticket = packet.getString("qr_ticket")
            require(id.matches(Regex("[0-9a-f]{32}")) && code.matches(Regex("[0-9]{6}")) && CloudQrProtocol.validSecret(ticket)) { "扫码响应参数无效" }
            val interval = packet.optLong("interval",3)
            require(interval in 3..60) { "扫码轮询间隔无效" }
            val wait = packet.optInt("default_wait_seconds",20)
            require(wait in 0..25) { "扫码等待时间无效" }
            val payload = if (flow == "receive") packet.getString("qr_payload").also { CloudQrProtocol.parse(it,"receive") } else null
            return CloudQrPending(id,flow,ticket,verifier,nonce,code,CloudQrProtocol.expiry(packet,now),interval*1000,payload,wait)
        }
    }
}
internal class CloudQrIdentity(val subject: String, val name: String, val accessToken: String) {
    override fun toString() = "CloudQrIdentity(redacted)"
}
internal class CloudQrApproval(val scanCode: String, val requestId: String, val account: String, val accountName: String,
    val application: String, val client: String, val permissions: List<String>, val confirmationCode: String, val expiresAt: Long) {
    override fun toString() = "CloudQrApproval(redacted)"
    companion object {
        fun parse(packet: JSONObject, code: String, owner: CloudQrIdentity, now: Long): CloudQrApproval {
            require(packet.getString("flow") == "receive" && packet.getString("status") == "pending" && packet.getString("target_kind") in setOf("web","app")) { "此登录请求已结束或无效" }
            val id = packet.getString("request_id"); val confirmation = packet.getString("confirmation_code")
            require(id.matches(Regex("[0-9a-f]{32}")) && confirmation.matches(Regex("[0-9]{6}"))) { "授权核对信息无效" }
            val app = packet.getString("application_name");val client = packet.getString("client_name")
            val rows = packet.getJSONArray("permissions")
            require(app.length in 1..200 && client.length in 1..200 && rows.length() in 1..32) { "授权目标信息无效" }
            val permissions = (0 until rows.length()).map { rows.getString(it).also { text -> require(text.length in 1..512) { "权限说明无效" } } }
            return CloudQrApproval(code,id,owner.subject,owner.name,app,client,permissions,confirmation,CloudQrProtocol.expiry(packet,now))
        }
    }
}
