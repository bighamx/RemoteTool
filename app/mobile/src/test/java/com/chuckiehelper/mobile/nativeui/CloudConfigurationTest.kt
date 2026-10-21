package com.chuckiehelper.mobile.nativeui

import android.content.Context
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
        for (name in listOf("MainActivity","app_updates","file-view","RemoteActivity","codex-$id","hermes-$id","codex-$other","hermes-$other"))
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
}
