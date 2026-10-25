package com.chuckiehelper.mobile.nativeui

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.chuckiehelper.mobile.phonecontrol.PhoneSettings
import com.chuckiehelper.mobile.tunnel.TunnelRule
import com.chuckiehelper.mobile.tunnel.TunnelRuntime
import com.chuckiehelper.mobile.tunnel.TunnelSettings
import com.chuckiehelper.mobile.tunnel.TunnelStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CloudConfigurationTest {
    private val id = "a".repeat(64)
    private val other = "b".repeat(64)
    private lateinit var context: Context
    private lateinit var configuration: CloudConfiguration
    @Before fun prepare() {
        context = RuntimeEnvironment.getApplication()
        for (name in listOf("MainActivity","app_updates","file-view","RemoteActivity","phone-tools","port-tunnels","codex-$id","hermes-$id","codex-$other","hermes-$other"))
            context.getSharedPreferences(name,0).edit().clear().commit()
        configuration = CloudConfiguration(context)
    }
    private fun device(key: String, url: String) = obj("id" to key,"name" to "Device $key","endpoints" to JSONArray(listOf(url)))
    private fun snapshot(vararg devices: JSONObject) = obj("schemaVersion" to 1,"devices" to JSONArray(devices.toList()),"settings" to JSONObject())

    @Test fun exportIncludesOnlyAllowlistedConfiguration() {
        context.getSharedPreferences("MainActivity",0).edit()
            .putString("devices",JSONArray(listOf(device(id,"https://device.example"))).toString())
            .putString("password_$id","private-password").putString("token_$id","private-token").commit()
        context.getSharedPreferences("codex-$id",0).edit().putString("draft","private-draft")
            .putString("pendingSubmissions","private-pending").putBoolean("codex_group_by_project",false).commit()
        val payload = configuration.export()
        val text = payload.toString()
        for (secret in listOf("private-password","private-token","private-draft","private-pending")) assertFalse(text.contains(secret))
        assertFalse(payload.getJSONObject("settings").getJSONObject("agents").getJSONObject(id).getJSONObject("codex").getBoolean("groupByProject"))
    }
    @Test fun importMergesAddressesPreservesCredentialsAndCreatesPrivateBackup() {
        val prefs = context.getSharedPreferences("MainActivity",0)
        prefs.edit().putString("devices",JSONArray(listOf(device(id,"https://old.example"),device(other,"http://local.example"))).toString())
            .putString("password_$id","keep-this").putString("last_device",other).commit()
        val incoming = device(id,"https://new.example/").put("preferredEndpoint","https://new.example/")
        configuration.apply(snapshot(incoming))
        val result = JSONArray(prefs.getString("devices","[]")).objects()
        assertEquals(2,result.size)
        assertEquals(setOf("https://old.example","https://new.example"),result.first{it.getString("id")==id}.array("endpoints").let{a->(0 until a.length()).map{a.getString(it)}.toSet()})
        assertEquals("keep-this",prefs.getString("password_$id",null))
        assertEquals(other,prefs.getString("last_device",null))
        assertEquals("https://new.example",prefs.getString("channel_$id",null))
        val backup = JSONObject(context.openFileInput("cloud-before-import.json").bufferedReader().use{it.readText()})
        assertEquals(2,backup.array("devices").length())
        assertFalse(backup.toString().contains("keep-this"))
    }
    @Test fun firstImportRequiresExplicitDeviceConnection() {
        configuration.apply(snapshot(device(id,"https://new.example")))
        assertTrue(context.getSharedPreferences("MainActivity",0).getBoolean("cloud_requires_connection_selection",false))
        assertFalse(context.getSharedPreferences("MainActivity",0).contains("last_device"))
    }
    @Test fun invalidImportDoesNotAlterLocalSettings() {
        val prefs = context.getSharedPreferences("MainActivity",0)
        prefs.edit().putString("devices","[]").putString("token_$id","unchanged").commit()
        val bad = snapshot(device(id,"https://user:password@device.example"))
        assertThrows(IllegalArgumentException::class.java){configuration.apply(bad)}
        assertEquals("[]",prefs.getString("devices",null))
        assertEquals("unchanged",prefs.getString("token_$id",null))
    }
    @Test fun failedMergeRollsBackWithoutOverwritingLocalOnlyPreferences() {
        val prefs = context.getSharedPreferences("MainActivity",0)
        val old = device(id,"https://old.example").put("endpoints",JSONArray((1..20).map{"https://old$it.example"}))
        val text = JSONArray(listOf(old)).toString()
        prefs.edit().putString("devices",text).putString("token_$id","unchanged").commit()
        assertThrows(java.io.IOException::class.java){configuration.apply(snapshot(device(id,"https://extra.example")))}
        assertEquals(text,prefs.getString("devices",null))
        assertEquals("unchanged",prefs.getString("token_$id",null))
    }

    private fun saveNewFeatures(): JSONObject {
        context.getSharedPreferences("MainActivity", 0).edit().putString("devices", JSONArray(listOf(device(id, "https://device.example"))).toString()).commit()
        PhoneSettings(context).apply {
            direct = false; advanced = true; root = true; shizuku = false
            port = 18792; address = "192.0.2.8"; connectionTab = "web"
            setRelayEnabled("https://device.example", true)
        }
        val rule = TunnelRule(1, "Test", "udp", "pc_to_phone", "127.0.0.1", 18080, "192.0.2.9", 8080)
        context.getSharedPreferences("port-tunnels", 0).edit().putString("rules-$id", JSONArray(listOf(rule.json().put("cookie", "private-rule-cookie"))).toString()).commit()
        return configuration.export()
    }

    @Test fun newFeaturesRoundTripWithoutExportingOrReplacingLocalIdentities() {
        val phone = context.getSharedPreferences("phone-tools", 0)
        phone.edit().putString("id", "private-phone-id").putString("token", "private-mcp-token")
            .putString("pairing-token", "private-pair-token").putString("web-secret", "private-web-binding").putBoolean("enabled", true).commit()
        val tunnels = context.getSharedPreferences("port-tunnels", 0)
        tunnels.edit().putString("client-id", "private-client-id").putString("run_id", "private-run-id").commit()
        val exported = saveNewFeatures()
        for (secret in listOf("private-phone-id", "private-mcp-token", "private-pair-token", "private-web-binding", "private-client-id", "private-run-id", "private-rule-cookie"))
            assertFalse(exported.toString().contains(secret))
        val tools = exported.getJSONObject("settings").getJSONObject("phoneTools")
        assertFalse(tools.getBoolean("direct")); assertTrue(tools.getBoolean("root"))
        assertEquals("web", tools.getString("connectionTab"))
        assertEquals(1, exported.getJSONObject("settings").getJSONObject("portTunnels").getJSONArray(id).length())

        phone.edit().clear().putString("id", "local-phone").putString("token", "local-token")
            .putString("pairing-token", "local-pair").putString("web-secret", "local-binding").commit()
        tunnels.edit().clear().putString("client-id", "local-client").commit()
        // Even unknown credential fields in an incoming document must never be applied.
        tools.put("token", "incoming-token").put("enabled", true)
        configuration.apply(exported)
        PhoneSettings(context).apply {
            assertFalse(enabled); assertFalse(direct); assertTrue(advanced); assertTrue(root); assertFalse(shizuku)
            assertEquals(18792, port); assertEquals("192.0.2.8", address); assertEquals("web", connectionTab)
            assertTrue(relayEnabled("https://device.example")); assertFalse(relayEnabled("https://other.example"))
        }
        assertEquals("local-phone", phone.getString("id", null)); assertEquals("local-token", phone.getString("token", null))
        assertEquals("local-pair", phone.getString("pairing-token", null)); assertEquals("local-binding", phone.getString("web-secret", null))
        assertFalse(phone.contains("enabled"))
        assertEquals("local-client", tunnels.getString("client-id", null))
        assertEquals("udp", TunnelSettings(context).rules(id).single().protocol)
        val backup = JSONObject(context.openFileInput("cloud-before-import.json").bufferedReader().use { it.readText() })
        assertTrue(backup.getJSONObject("settings").has("phoneTools"))
        assertFalse(backup.toString().contains("local-token"))
    }

    @Test fun olderSnapshotsLeaveNewFeatureSettingsAlone() {
        saveNewFeatures()
        val phone = context.getSharedPreferences("phone-tools", 0).all.toMap()
        val tunnels = context.getSharedPreferences("port-tunnels", 0).all.toMap()
        configuration.apply(snapshot(device(id, "https://device.example")))
        assertEquals(phone, context.getSharedPreferences("phone-tools", 0).all)
        assertEquals(tunnels, context.getSharedPreferences("port-tunnels", 0).all)
    }

    @Test fun invalidFeatureConfigurationIsRejectedBeforeAnyWrites() {
        val saved = saveNewFeatures()
        val phoneBefore = context.getSharedPreferences("phone-tools", 0).all.toMap()
        val tunnelBefore = context.getSharedPreferences("port-tunnels", 0).all.toMap()
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.getJSONObject("phoneTools").put("port", 80) },
            { it.getJSONObject("phoneTools").put("root", "true") },
            { it.getJSONObject("phoneTools").put("address", "user:password@device.example") },
            { it.getJSONObject("phoneTools").getJSONObject("relays").put("https://unknown.example", true) },
            { it.getJSONObject("portTunnels").put(other, JSONArray()) },
            { it.getJSONObject("portTunnels").getJSONArray(id).put("bad-row") },
            { it.getJSONObject("portTunnels").getJSONArray(id).put(it.getJSONObject("portTunnels").getJSONArray(id).getJSONObject(0)) },
            { it.getJSONObject("portTunnels").getJSONArray(id).getJSONObject(0).put("listen_port", 1) },
        )
        for (mutate in mutations) {
            val incoming = JSONObject(saved.toString())
            mutate(incoming.getJSONObject("settings"))
            assertThrows(IllegalArgumentException::class.java) { configuration.apply(incoming) }
            assertEquals(phoneBefore, context.getSharedPreferences("phone-tools", 0).all)
            assertEquals(tunnelBefore, context.getSharedPreferences("port-tunnels", 0).all)
        }
    }

    @Test fun applyingTunnelSettingsDoesNotInterruptRunningTunnel() {
        val saved = saveNewFeatures()
        val previous = context.getSharedPreferences("port-tunnels", 0).all.toMap()
        TunnelRuntime.update(TunnelStatus(deviceId = id, running = true))
        try {
            val error = assertThrows(IllegalArgumentException::class.java) { configuration.apply(saved) }
            assertTrue(error.message!!.contains("停止端口转发"))
            assertEquals(previous, context.getSharedPreferences("port-tunnels", 0).all)
            assertTrue(TunnelRuntime.status.value.running)
        } finally { TunnelRuntime.update(TunnelStatus()) }
    }

    @Test fun failedTunnelSaveRollsBackAlreadyAppliedPhoneSettingsAndKeepsCredentials() {
        val incoming = saveNewFeatures()
        val phone = context.getSharedPreferences("phone-tools", 0)
        phone.edit().putBoolean("direct", true).putBoolean("root", false).putInt("port", 19876)
            .putString("token", "keep-local-token").commit()
        val tunnelPrefs = context.getSharedPreferences("port-tunnels", 0)
        tunnelPrefs.edit().putString("rules-$id", "[]").putString("client-id", "keep-local-client").commit()
        val beforePhone = phone.all.toMap()
        val beforeTunnel = tunnelPrefs.all.toMap()
        var failSave = true
        val failingContext = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val original = super.getSharedPreferences(name, mode)
                if (name != "port-tunnels") return original
                return object : SharedPreferences by original {
                    override fun edit(): SharedPreferences.Editor {
                        val editor = original.edit()
                        return object : SharedPreferences.Editor by editor {
                            override fun commit(): Boolean {
                                if (failSave) { failSave = false; return false }
                                return editor.commit()
                            }
                        }
                    }
                }
            }
        }
        val failure = assertThrows(java.io.IOException::class.java) { CloudConfiguration(failingContext).apply(incoming) }
        assertTrue(failure.message!!.contains("已恢复本机原配置"))
        assertFalse(failSave)
        assertEquals(beforePhone, phone.all)
        assertEquals(beforeTunnel, tunnelPrefs.all)
    }
}
