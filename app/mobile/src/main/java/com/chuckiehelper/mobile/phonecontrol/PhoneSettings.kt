package com.chuckiehelper.mobile.phonecontrol

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64
import org.json.JSONObject

class PhoneSettings(context: Context) {
    private val prefs = context.getSharedPreferences("phone-tools", Context.MODE_PRIVATE)
    // Admission is process-local. A saved preference cannot prove that a service survived.
    @Volatile var enabled: Boolean = false
    var direct: Boolean get() = prefs.getBoolean("direct", true)
        set(value) { prefs.edit().putBoolean("direct", value).apply() }
    var advanced: Boolean get() = prefs.getBoolean("advanced", false)
        set(value) { prefs.edit().putBoolean("advanced", value).apply() }
    var root: Boolean get() = prefs.getBoolean("root", false)
        set(value) { prefs.edit().putBoolean("root", value).apply() }
    var shizuku: Boolean get() = prefs.getBoolean("shizuku", true)
        set(value) { prefs.edit().putBoolean("shizuku", value).apply() }
    var port: Int get() = prefs.getInt("port", 18791)
        set(value) { require(value in 1024..65535); prefs.edit().putInt("port", value).apply() }
    var agentOverlay: Boolean get() = prefs.getBoolean("agent-overlay", true)
        set(value) { prefs.edit().putBoolean("agent-overlay", value).apply() }
    var agentIsland: Boolean get() = prefs.getBoolean("agent-island", true)
        set(value) { prefs.edit().putBoolean("agent-island", value).apply() }
    var address: String get() = prefs.getString("address", "").orEmpty()
        set(value) { prefs.edit().putString("address", value).commit() }
    var connectionTab: String get() = prefs.getString("connection-tab", "").orEmpty()
        set(value) { prefs.edit().putString("connection-tab", value).commit() }
    fun relayEnabled(base: String): Boolean {
        val key = relayPreferenceKey(base)
        if (!prefs.contains(key) && prefs.getBoolean("enabled", false) && !direct) {
            // Old releases could only run with direct disabled by selecting Web relay.
            // Restore that selection once; never start a service or reuse credentials here.
            prefs.edit().putBoolean(key, true).remove("enabled").commit()
        }
        return prefs.getBoolean(key, false)
    }
    fun setRelayEnabled(base: String, value: Boolean) {
        prefs.edit().putBoolean(relayPreferenceKey(base), value).remove("enabled").commit()
    }
    val id: String get() = stored("id") { "phone-" + java.util.UUID.randomUUID() }
    val token: String get() = stored("token") { randomToken() }
    val pairingToken: String get() = stored("pairing-token") { randomToken() }
    fun rotateToken() { prefs.edit().putString("token", randomToken()).commit() }
    private fun webKey(base: String) = "web-" + MessageDigest.getInstance("SHA-256").digest(base.toByteArray()).joinToString("") { "%02x".format(it) }
    fun webConnection(base: String): String? = prefs.getString(webKey(base), null)
    fun saveWebConnection(base: String, value: String?) { prefs.edit().apply { if (value == null) remove(webKey(base)) else putString(webKey(base), value) }.commit() }
    @Synchronized private fun stored(key: String, create: () -> String): String = prefs.getString(key, null)
        ?: create().also { prefs.edit().putString(key, it).commit() }
    fun authenticate(value: String): Boolean = MessageDigest.isEqual(token.toByteArray(), value.toByteArray())
    fun description() = JSONObject().put("id", id).put("name", android.os.Build.MODEL).put("online", enabled)
    companion object {
        internal fun relayPreferenceKey(base: String) = "relay-enabled-web-" + MessageDigest.getInstance("SHA-256")
            .digest(base.toByteArray()).joinToString("") { "%02x".format(it) }
        private fun randomToken() = Base64.encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE)
    }
}

class PhoneToolError(val code: String, message: String) : Exception(message)
internal fun fail(code: String, message: String): Nothing = throw PhoneToolError(code, message)
