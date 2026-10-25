package com.chuckiehelper.mobile.nativeui

import android.content.Context
import com.chuckiehelper.mobile.phonecontrol.PhoneControlService
import com.chuckiehelper.mobile.phonecontrol.PhoneSettings
import com.chuckiehelper.mobile.tunnel.TunnelRule
import com.chuckiehelper.mobile.tunnel.TunnelRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/** Portable preferences only; identities, bindings and runtime state stay on this installation. */
internal class PhoneCloudConfiguration(private val context: Context) {
    private val phone get() = context.getSharedPreferences("phone-tools", 0)
    private val tunnels get() = context.getSharedPreferences("port-tunnels", 0)
    private val booleans = mapOf("direct" to true, "advanced" to false, "root" to false, "shizuku" to true)
    private val displayBooleans = mapOf("agent-overlay" to true, "agent-island" to true)

    fun export(devices: JSONArray): Pair<JSONObject, JSONObject> {
        val settings = PhoneSettings(context)
        val relays = JSONObject()
        for (base in endpoints(devices)) relays.put(base, settings.relayEnabled(base))
        val tools = JSONObject().apply {
            booleans.forEach { (key, default) -> put(key, phone.getBoolean(key, default)) }
            displayBooleans.forEach { (key, default) -> put(key, phone.getBoolean(key, default)) }
            put("port", settings.port); put("address", settings.address)
            put("connectionTab", settings.connectionTab); put("relays", relays)
        }
        val rules = JSONObject()
        for (device in devices.objects()) {
            val id = device.getString("id")
            // Do not instantiate TunnelSettings: exporting must not generate a client identity.
            val saved = JSONArray(tunnels.getString("rules-$id", "[]"))
            require(saved.length() == saved.objects().size) { "本机转发规则格式无效" }
            rules.put(id, JSONArray(saved.objects().map { TunnelRule.parse(it).json() }))
        }
        return tools to rules
    }

    fun validate(settings: JSONObject, devices: JSONArray) {
        if (settings.has("phoneTools")) {
            val tools = settings.getJSONObject("phoneTools")
            booleans.keys.forEach { require(tools.get(it) is Boolean) { "手机操控开关无效" } }
            displayBooleans.keys.forEach { if (tools.has(it)) require(tools.get(it) is Boolean) { "Agent 提示开关无效" } }
            require(tools.get("port") is Int && tools.getInt("port") in 1024..65535) { "手机操控端口无效" }
            val address = tools.getString("address")
            require(tools.get("address") is String && address.length <= 253 &&
                (address.isEmpty() || runCatching {
                    val uri = URI("http://$address")
                    uri.host != null && uri.userInfo == null && uri.port == -1 && uri.query == null && uri.fragment == null && uri.rawPath.isNullOrEmpty()
                }.getOrDefault(false))) { "手机访问地址无效" }
            require(tools.get("connectionTab") is String && tools.getString("connectionTab") in listOf("", "direct", "web")) { "手机操控配置页无效" }
            val relays = tools.getJSONObject("relays")
            val known = endpoints(devices)
            for (base in relays.keys()) require(base in known && relays.get(base) is Boolean) { "Web 转发地址或开关无效" }
        }
        if (settings.has("portTunnels")) {
            val rules = settings.getJSONObject("portTunnels")
            val known = devices.objects().map { it.getString("id") }.toSet()
            for (device in rules.keys()) {
                require(device in known) { "端口转发所属设备不存在" }
                val rows = rules.getJSONArray(device)
                require(rows.length() <= 32) { "每台设备最多支持 32 条转发规则" }
                require(rows.length() == rows.objects().size) { "转发规则格式无效" }
                val ids = mutableSetOf<Int>()
                for (row in rows.objects()) {
                    for (key in listOf("id", "listen_port", "target_port")) require(row.get(key) is Int) { "转发规则端口或编号无效" }
                    for (key in listOf("name", "protocol", "direction", "listen_host", "target_host")) require(row.get(key) is String) { "转发规则文本无效" }
                    val rule = TunnelRule.parse(row)
                    rule.validate()
                    require(ids.add(rule.id)) { "转发规则编号重复" }
                }
            }
        }
    }

    fun requireStopped(settings: JSONObject) {
        if (settings.has("phoneTools")) require(!PhoneControlService.running && !PhoneControlService.starting) { "请先停止手机操控，再应用云配置" }
        if (settings.has("portTunnels")) require(!TunnelRuntime.status.value.running) { "请先停止端口转发，再应用云配置" }
    }

    fun apply(settings: JSONObject) {
        settings.optJSONObject("phoneTools")?.let { tools ->
            val editor = phone.edit().remove("enabled")
            booleans.keys.forEach { editor.putBoolean(it, tools.getBoolean(it)) }
            displayBooleans.forEach { (key, default) -> editor.putBoolean(key, tools.optBoolean(key, default)) }
            editor.putInt("port", tools.getInt("port")).putString("address", tools.getString("address"))
                .putString("connection-tab", tools.getString("connectionTab"))
            val relays = tools.getJSONObject("relays")
            for (base in relays.keys()) editor.putBoolean(PhoneSettings.relayPreferenceKey(base), relays.getBoolean(base))
            check(editor.commit()) { "手机操控配置保存失败" }
        }
        settings.optJSONObject("portTunnels")?.let { rules ->
            val editor = tunnels.edit()
            for (device in rules.keys()) {
                val clean = JSONArray(rules.getJSONArray(device).objects().map { TunnelRule.parse(it).json() })
                editor.putString("rules-$device", clean.toString())
            }
            check(editor.commit()) { "端口转发配置保存失败" }
        }
    }

    private fun endpoints(devices: JSONArray) = devices.objects().flatMap { device ->
        val urls = device.getJSONArray("endpoints")
        (0 until urls.length()).map { urls.getString(it).trimEnd('/') }
    }.toSet()
}
