package com.chuckiehelper.mobile.startup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class StartupSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("boot-startup", Context.MODE_PRIVATE)
    var enabled: Boolean get() = prefs.getBoolean("enabled", false)
        set(value) { prefs.edit().putBoolean("enabled", value).commit() }
    var phoneTools: Boolean get() = prefs.getBoolean("phone-tools", true)
        set(value) { prefs.edit().putBoolean("phone-tools", value).commit() }
    var phoneRelay: Boolean get() = prefs.getBoolean("phone-relay", true)
        set(value) { prefs.edit().putBoolean("phone-relay", value).commit() }
    var portForward: Boolean get() = prefs.getBoolean("port-forward", true)
        set(value) { prefs.edit().putBoolean("port-forward", value).commit() }
    var result: String get() = prefs.getString("result", "尚未执行").orEmpty()
        set(value) { prefs.edit().putString("result", value.take(240)).apply() }
    data class Target(val id: String, val name: String, val addresses: List<String>, val preferred: String?)
    private fun rows(): List<JSONObject> {
        val array = JSONArray(context.getSharedPreferences("MainActivity", 0).getString("devices", "[]"))
        return (0 until array.length()).map { array.getJSONObject(it) }
    }
    fun target(kind: String): Target? {
        val main = context.getSharedPreferences("MainActivity", 0)
        val id = prefs.getString("$kind-device", null) ?: main.getString("last_device", null) ?: return null
        val row = rows().firstOrNull { it.optString("id") == id } ?: return null
        val endpoints = row.optJSONArray("endpoints") ?: return null
        val addresses = (0 until endpoints.length()).map { endpoints.getString(it).trimEnd('/') }.distinct()
        val preferred = prefs.getString("$kind-address", null) ?: main.getString("channel_$id", null)
        return Target(id, row.optString("name", "电脑"), addresses, preferred?.takeIf { it in addresses })
    }
    fun rememberPhone(base: String?) {
        if (base.isNullOrBlank()) return
        val address = base.trimEnd('/')
        val row = rows().firstOrNull { row -> row.optJSONArray("endpoints")?.let { array ->
            (0 until array.length()).any { array.getString(it).trimEnd('/') == address }
        } == true } ?: return
        prefs.edit().putString("phone-device", row.getString("id")).putString("phone-address", address).commit()
    }
    fun rememberTunnel(id: String, base: String) {
        prefs.edit().putString("tunnel-device", id).putString("tunnel-address", base.trimEnd('/')).commit()
    }
}
