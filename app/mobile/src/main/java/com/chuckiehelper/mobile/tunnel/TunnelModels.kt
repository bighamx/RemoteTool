package com.chuckiehelper.mobile.tunnel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class TunnelRule(val id: Int, val name: String, val protocol: String, val direction: String,
    val listenHost: String, val listenPort: Int, val targetHost: String, val targetPort: Int) {
    val phoneListens get() = direction == "phone_to_pc"
    val udp get() = protocol == "udp"
    fun validate() {
        require(id in 1..32 && name.length <= 80 && protocol in listOf("tcp", "udp") && direction in listOf("phone_to_pc", "pc_to_phone")) { "无效的转发规则" }
        require(listenHost in listOf("127.0.0.1", "0.0.0.0", "::1", "::") || android.util.Patterns.IP_ADDRESS.matcher(listenHost).matches()) { "监听地址需填写 IP" }
        require(listenPort in 1024..65535 && targetPort in 1..65535) { "监听端口范围 1024–65535，目标端口范围 1–65535" }
        require(targetHost.isNotBlank() && targetHost.length <= 253 && targetHost.none { it.isWhitespace() || it == '/' || it == '\\' }) { "目标需填写 IP 或主机名，不包含协议和路径" }
    }
    fun json() = JSONObject().put("id", id).put("name", name).put("protocol", protocol).put("direction", direction)
        .put("listen_host", listenHost).put("listen_port", listenPort).put("target_host", targetHost).put("target_port", targetPort)
    companion object {
        fun parse(json: JSONObject) = TunnelRule(json.getInt("id"), json.optString("name"), json.getString("protocol"), json.getString("direction"),
            json.getString("listen_host"), json.getInt("listen_port"), json.getString("target_host"), json.getInt("target_port"))
    }
}

class TunnelSettings(context: Context) {
    private val prefs = context.getSharedPreferences("port-tunnels", Context.MODE_PRIVATE)
    val clientId: String = prefs.getString("client-id", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("client-id", it).commit() }
    fun rules(device: String): List<TunnelRule> {
        val array = JSONArray(prefs.getString("rules-$device", "[]"))
        return (0 until array.length()).map { TunnelRule.parse(array.getJSONObject(it)) }
    }
    fun save(device: String, rules: List<TunnelRule>) {
        require(rules.size <= 32); rules.forEach { it.validate() }
        prefs.edit().putString("rules-$device", JSONArray().apply { rules.forEach { put(it.json()) } }.toString()).apply()
    }
}

data class TunnelStatus(val deviceId: String = "", val name: String = "", val state: String = "未开启",
    val running: Boolean = false, val active: Int = 0, val sent: Long = 0, val received: Long = 0, val dropped: Long = 0, val error: String? = null)

internal data class TunnelFrame(val type: Int, val id: Int, val aux: Int = 0, val payload: ByteArray = byteArrayOf()) {
    fun encode(): ByteArray = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.BIG_ENDIAN)
        .put(1.toByte()).put(type.toByte()).putShort(0.toShort()).putInt(id).putInt(aux).put(payload).array()
    companion object {
        const val OPEN = 1; const val OPENED = 2; const val DATA = 3; const val FIN = 4; const val CLOSE = 5
        const val WINDOW = 6; const val DATAGRAM = 7; const val ERROR = 8
        const val HEADER = 12; const val CHUNK = 16384; const val CREDIT = 65536; const val MAX_UDP = 65507; const val MAX_FRAME = HEADER + MAX_UDP
        fun decode(raw: ByteArray): TunnelFrame {
            require(raw.size in HEADER..MAX_FRAME && raw[0].toInt() == 1 && raw[2].toInt() == 0 && raw[3].toInt() == 0) { "无效的隧道帧" }
            val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN); buffer.position(4)
            val type = raw[1].toInt(); val id = buffer.int; val aux = buffer.int; val size = raw.size - HEADER
            require(id > 0 && type in OPEN..ERROR && (type != DATA || size in 1..CHUNK) &&
                (type !in listOf(OPEN, OPENED, FIN, CLOSE, WINDOW) || size == 0) && (type != ERROR || size <= 1024)) { "无效的隧道消息" }
            return TunnelFrame(type, id, aux, raw.copyOfRange(HEADER, raw.size))
        }
    }
}
