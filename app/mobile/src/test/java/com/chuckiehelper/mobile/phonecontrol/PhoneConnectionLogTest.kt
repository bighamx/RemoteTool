package com.chuckiehelper.mobile.phonecontrol

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneConnectionLogTest {
    private lateinit var context: Context
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("phone-connections.db"); context.deleteDatabase("phone-tools.db") }
    @Test fun historySurvivesRestartAndUnknownCallsAreNotReportedAsSuccess() {
        val first = PhoneConnectionLog(context)
        val id = first.begin("a", "Codex", "r", "phone_shell", JSONObject().put("command", "echo hello"))
        first.close()
        val restored = PhoneConnectionLog(context)
        assertEquals(0, restored.connections(false).size)
        assertEquals("process_restarted", restored.connections(true).single().getString("reason"))
        assertEquals("delivery_unknown", restored.call(id)!!.getString("state"))
        restored.close()
    }
    @Test fun manualDisconnectBlocksOnlyThatSessionAndPersistsAcrossRestart() {
        val store = PhoneConnectionLog(context)
        store.seen("a", "Codex"); store.seen("b", "Hermes")
        val queued = store.begin("a", "Codex", "q", "phone_tap", JSONObject(), queued = true)
        store.disconnect("a", "user_disconnected", byUser = true)
        try { store.requireOpen("a"); fail("must reject disconnected client") } catch (error: PhoneToolError) { assertEquals("connection_closed_by_user", error.code) }
        store.requireOpen("b"); store.seen("new-a", "Codex")
        store.finish(queued, PhoneEngine.errorResult(PhoneToolError("connection_closed_by_user", "用户断开")))
        assertEquals("connection_closed_by_user", store.call(queued)!!.getString("state"))
        store.clearHistory()
        assertTrue(store.blocked("a"))
        assertEquals(2, store.connections(false).size)
        store.close()
        val restarted = PhoneConnectionLog(context)
        assertTrue(restarted.blocked("a")); restarted.requireOpen("new-a"); restarted.close()
    }
    @Test fun auditSanitizationOmitsBinaryAndSecretsAndKeepsValidBoundedJson() {
        val source = JSONObject().put("password", "private-password").put("Authorization", "Bearer secret")
            .put("nested", JSONObject().put("api_key", "private-key").put("data_base64", "A".repeat(500_000)))
            .put("output", "long-output".repeat(10_000))
        val text = PhoneConnectionLog.safeJson(source)
        val result = JSONObject(text)
        assertFalse(text.contains("private-password")); assertFalse(text.contains("Bearer secret")); assertFalse(text.contains("private-key"))
        assertTrue(text.length < 12_000)
        assertTrue(result.getString("output").contains("已截断"))
        assertEquals(500_000, source.getJSONObject("nested").getString("data_base64").length)
    }
    @Test fun rejectedOldSessionIsExplicitWhileNewClientCanContinue() {
        context.getSharedPreferences("phone-tools", 0).edit().clear().commit()
        val engine = PhoneEngine(context); engine.settings.enabled = true
        engine.connectionSeen("direct:old", "Codex")
        engine.disconnect("direct:old", byUser = true)
        val rejected = engine.invoke("phone_get_capabilities", JSONObject(), "new-request", agentId = "direct:old")
        val data = rejected.getJSONObject("structuredContent")
        assertEquals("connection_closed_by_user", data.getString("code"))
        assertFalse(data.getBoolean("retryable"))
        assertTrue(data.getJSONObject("details").getBoolean("requires_reinitialize"))
        assertFalse(engine.invoke("phone_get_capabilities", JSONObject(), "new-client-request", agentId = "direct:new").getBoolean("isError"))
    }
}
