package com.chuckiehelper.mobile.nativeui

import android.content.Context
import com.chuckiehelper.mobile.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.io.IOException

/** Explicit allowlist: device passwords are portable; tokens, cookies and conversations are not. */
internal class CloudConfiguration(private val context: Context,
    private val credentials: DeviceCredentialStore = DeviceCredentialStore(context)) {
    companion object { const val MAX_BYTES = 512 * 1024 }
    private val main get() = context.getSharedPreferences("MainActivity", 0)

    fun export(includeCredentials: Boolean = true): JSONObject {
        val devices = JSONArray()
        for (saved in JSONArray(main.getString("devices", "[]")).objects()) {
            val device = obj("id" to saved.getString("id"), "name" to saved.getString("name"),
                "endpoints" to JSONArray((0 until saved.getJSONArray("endpoints").length()).map { saved.getJSONArray("endpoints").getString(it) }))
            val addresses = device.getJSONArray("endpoints")
            main.getString("channel_${device.getString("id")}", null)
                ?.takeIf { preferred -> (0 until addresses.length()).any { addresses.getString(it) == preferred } }
                ?.let { device.put("preferredEndpoint", it) }
            if (includeCredentials) credentials.read(device.getString("id"))?.takeIf {
                it.username.isNotBlank() && it.password.isNotEmpty()
            }?.let { device.put("credentials", obj("username" to it.username, "password" to it.password)) }
            devices.put(device)
        }
        val updates = context.getSharedPreferences("app_updates", 0)
        val files = context.getSharedPreferences("file-view", 0)
        val (phoneTools, portTunnels) = PhoneCloudConfiguration(context).export(devices)
        val agents = JSONObject()
        for (i in 0 until devices.length()) {
            val id = devices.getJSONObject(i).getString("id")
            agents.put(id, JSONObject().apply {
                for (agent in listOf("codex", "hermes")) {
                    val prefs = context.getSharedPreferences("$agent-$id", 0)
                    put(agent, obj("groupByProject" to prefs.getBoolean("codex_group_by_project", true),
                        "collapsedGroups" to JSONArray(prefs.getStringSet("codex_collapsed_groups", setOf("已存档"))!!.toList())))
                }
            })
        }
        return validate(obj("schemaVersion" to 1, "appVersion" to BuildConfig.VERSION_NAME,
            "exportedAt" to Instant.now().toString(), "devices" to devices,
            "settings" to obj(
                "appearance" to AppAppearance.from(main.getString(AppAppearance.KEY, null)).value,
                "phoneTools" to phoneTools, "portTunnels" to portTunnels,
                "updates" to obj("automatic" to updates.getBoolean("automatic", true), "wifiOnly" to updates.getBoolean("wifi_only", true)),
                "fileView" to obj("sort" to files.getString("sort", "NAME"), "secondarySort" to files.getString("secondarySort", null),
                    "descending" to files.getBoolean("descending", false), "secondaryDescending" to files.getBoolean("secondaryDescending", false),
                    "foldersFirst" to files.getBoolean("foldersFirst", true)),
                "remoteQuality" to context.getSharedPreferences("RemoteActivity", 0).getInt("quality", 1), "agents" to agents)))
    }

    fun validate(payload: JSONObject): JSONObject {
        require(payload.toString().toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "云配置超过 512 KB 限制" }
        require(payload.get("schemaVersion") is Int && payload.getInt("schemaVersion") == 1) { "不支持此配置版本，请更新 App" }
        val devices = payload.getJSONArray("devices")
        require(devices.length() <= 200) { "配置最多支持 200 台设备" }
        val ids = mutableSetOf<String>()
        for (i in 0 until devices.length()) {
            val d = devices.getJSONObject(i)
            val id = d.getString("id")
            require(id.matches(Regex("[0-9a-f]{64}")) && ids.add(id)) { "设备标识无效或重复" }
            require(d.getString("name").isNotBlank() && d.getString("name").length <= 200 && !d.getString("name").any { it.isISOControl() }) { "设备名称无效" }
            val endpoints = d.getJSONArray("endpoints")
            require(endpoints.length() <= 20) { "设备地址过多" }
            val addresses = (0 until endpoints.length()).map { n ->
                val raw = endpoints.getString(n)
                require(raw.length <= 2048) { "设备地址过长" }
                val uri = URI(raw)
                require(uri.scheme in listOf("http", "https") && uri.host != null && uri.userInfo == null &&
                    uri.query == null && uri.fragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") &&
                    (uri.port == -1 || uri.port in 1..65535) && !raw.contains('\\')) { "设备地址必须是 HTTP 或 HTTPS 根地址" }
                raw
            }
            require(addresses.distinct().size == addresses.size) { "设备地址重复" }
            if (d.has("preferredEndpoint")) require(d.getString("preferredEndpoint") in addresses) { "首选通道不在设备地址中" }
            if (d.has("credentials")) {
                val login = d.optJSONObject("credentials")
                require(login != null && login.opt("username") is String && login.opt("password") is String) { "设备登录信息格式无效" }
                require(login.getString("username").isNotBlank() && login.getString("username").length <= 512 &&
                    login.getString("password").isNotEmpty() && login.getString("password").length <= 4096) { "设备用户名或密码无效" }
            }
        }
        val settings = payload.getJSONObject("settings")
        if (settings.has("appearance")) require(settings.get("appearance") is String && settings.getString("appearance") in AppAppearance.entries.map { it.value }) { "外观设置无效" }
        settings.optJSONObject("updates")?.let { p -> for (key in listOf("automatic", "wifiOnly")) require(p.get(key) is Boolean) { "更新设置无效" } }
        settings.optJSONObject("fileView")?.let { p ->
            require(p.getString("sort") in FileSort.entries.map { it.name }) { "文件排序无效" }
            if (p.has("secondarySort")) require(p.getString("secondarySort") in FileSort.entries.map { it.name }) { "次级文件排序无效" }
            for (key in listOf("descending", "secondaryDescending", "foldersFirst")) require(p.get(key) is Boolean) { "文件设置无效" }
        }
        if (settings.has("remoteQuality")) require(settings.get("remoteQuality") is Int && settings.getInt("remoteQuality") in 0..4) { "画质设置无效" }
        settings.optJSONObject("agents")?.let { agents ->
            for (id in agents.keys()) {
                require(id in ids) { "会话设置所属设备不存在" }
                val row = agents.getJSONObject(id)
                for (agent in row.keys()) {
                    require(agent in listOf("codex", "hermes")) { "未知 Agent 设置" }
                    val prefs = row.getJSONObject(agent)
                    require(prefs.get("groupByProject") is Boolean) { "分组设置无效" }
                    val groups = prefs.getJSONArray("collapsedGroups")
                    require(groups.length() <= 500) { "折叠分组过多" }
                    for (n in 0 until groups.length()) require(groups.getString(n).isNotBlank() && groups.getString(n).length <= 512 &&
                        !groups.getString(n).any { it.isISOControl() }) { "分组名称无效或过长" }
                }
            }
        }
        PhoneCloudConfiguration(context).validate(settings, devices)
        return payload
    }

    /** Keep a private local rollback snapshot; merge devices by identity and apply only known settings. */
    fun apply(payload: JSONObject) {
        validate(payload)
        PhoneCloudConfiguration(context).requireStopped(payload.getJSONObject("settings"))
        // A local rollback backup must not create a second plaintext password store.
        val backup = export(includeCredentials = false).put("_localEncryptedCredentials",
            JSONObject(context.getSharedPreferences("device-credentials", 0).all))
        context.openFileOutput("cloud-before-import.json", Context.MODE_PRIVATE).use { it.write(backup.toString().toByteArray(Charsets.UTF_8)) }
        val names = mutableSetOf("MainActivity", "app_updates", "file-view", "RemoteActivity", "phone-tools", "port-tunnels", "device-credentials")
        for (device in payload.getJSONArray("devices").objects()) for (agent in listOf("codex", "hermes")) names.add("$agent-${device.getString("id")}")
        val previous = names.associateWith { name -> context.getSharedPreferences(name, 0).all.mapValues { (_, value) ->
            if (value is Set<*>) value.toSet() else value
        } }
        try { applyValidated(payload) }
        catch (failure: Exception) {
            var restored = true
            for ((name, values) in previous) {
                val rollback = context.getSharedPreferences(name, 0).edit().clear()
                for ((key, value) in values) when (value) {
                    is String -> rollback.putString(key, value)
                    is Boolean -> rollback.putBoolean(key, value)
                    is Int -> rollback.putInt(key, value)
                    is Long -> rollback.putLong(key, value)
                    is Float -> rollback.putFloat(key, value)
                    is Set<*> -> rollback.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                if (!rollback.commit()) restored = false
            }
            throw IOException(if (restored) "配置应用失败，已恢复本机原配置：${failure.message}"
                else "配置保存失败，部分设置可能已变化；旧配置已保存在本机私有备份中", failure)
        }
    }

    private fun applyValidated(payload: JSONObject) {
        val existing = JSONArray(main.getString("devices", "[]")).objects().associateBy { it.getString("id") }.toMutableMap()
        val editor = main.edit()
        for (d in payload.getJSONArray("devices").objects()) {
            val id = d.getString("id")
            val urls = (existing[id]?.array("endpoints")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() +
                d.array("endpoints").let { a -> (0 until a.length()).map { a.getString(it).trimEnd('/') } }).distinct()
            require(urls.size <= 20) { "合并后设备地址超过 20 个，请先移除不用的地址" }
            existing[id] = obj("id" to id, "name" to d.getString("name"), "endpoints" to JSONArray(urls))
            if (d.has("preferredEndpoint")) editor.putString("channel_$id", d.getString("preferredEndpoint").trimEnd('/'))
        }
        require(existing.size <= 200) { "合并后设备超过 200 台" }
        if (!main.contains("last_device") && payload.getJSONArray("devices").length() > 0)
            editor.putBoolean("cloud_requires_connection_selection", true)
        payload.getJSONObject("settings").optString("appearance").takeIf { it.isNotEmpty() }
            ?.let { editor.putString(AppAppearance.KEY, it) }
        check(editor.putString("devices", JSONArray(existing.values.toList()).toString()).commit()) { "设备配置保存失败" }
        for (device in payload.getJSONArray("devices").objects()) {
            val login = device.optJSONObject("credentials") ?: continue
            val id = device.getString("id")
            val username = login.getString("username")
            val password = login.getString("password")
            val old = credentials.read(id)
            val token = old?.takeIf { it.username == username && it.password == password }?.token.orEmpty()
            credentials.save(id, DeviceLogin(username, password, token))
        }
        val settings = payload.getJSONObject("settings")
        settings.optJSONObject("updates")?.let { p -> check(context.getSharedPreferences("app_updates", 0).edit()
            .putBoolean("automatic", p.getBoolean("automatic")).putBoolean("wifi_only", p.getBoolean("wifiOnly")).commit()) { "更新设置保存失败" } }
        settings.optJSONObject("fileView")?.let { p -> check(context.getSharedPreferences("file-view", 0).edit()
            .putString("sort", p.getString("sort")).putString("secondarySort", p.optString("secondarySort").takeIf { it.isNotEmpty() })
            .putBoolean("descending", p.getBoolean("descending")).putBoolean("secondaryDescending", p.getBoolean("secondaryDescending"))
            .putBoolean("foldersFirst", p.getBoolean("foldersFirst")).commit()) { "文件设置保存失败" } }
        if (settings.has("remoteQuality")) check(context.getSharedPreferences("RemoteActivity", 0).edit()
            .putInt("quality", settings.getInt("remoteQuality")).commit()) { "画质设置保存失败" }
        settings.optJSONObject("agents")?.let { agents -> for (id in agents.keys()) {
            val row = agents.getJSONObject(id)
            for (agent in row.keys()) {
                val p = row.getJSONObject(agent)
                val groups = p.getJSONArray("collapsedGroups")
                check(context.getSharedPreferences("$agent-$id", 0).edit().putBoolean("codex_group_by_project", p.getBoolean("groupByProject"))
                    .putStringSet("codex_collapsed_groups", (0 until groups.length()).map { groups.getString(it) }.toSet()).commit()) { "会话显示设置保存失败" }
            }
        } }
        PhoneCloudConfiguration(context).apply(settings)
    }
}
