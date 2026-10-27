package com.chuckiehelper.mobile.phonecontrol

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneReadingOptionsTest {
    private val screen get() = JSONObject().put("screen_width", 1080).put("screen_height", 2400)
    @Test fun fullDumpFiltersBeforeBudgetMasksPasswordsAndProducesText() {
        val xml = """<hierarchy><node package="app.qq" text="noise" bounds="[0,0][100,100]"/>
            <node package="app.qq" text="消息正文" resource-id="app.qq:id/message" bounds="[10,100][400,200]"/>
            <node package="app.qq" text="secret" password="true" bounds="[0,0][100,100]"/></hierarchy>"""
        val options = JSONObject().put("mode", "text").put("filter", JSONObject().put("text_contains", "消息"))
        val data = PhoneUiDump.parse(xml, 1, options, screen, "root")
        assertEquals("uiautomator", data.getString("backend"))
        assertEquals("root", data.getString("privilege_backend"))
        assertEquals("消息正文", data.getString("text"))
        assertEquals(1, data.getJSONArray("nodes").length())
        assertFalse(data.getBoolean("node_actions"))
        assertTrue(data.getBoolean("truncated"))
        val all = PhoneUiDump.parse(xml, 20, JSONObject().put("mode", "raw"), screen, "root")
        assertEquals("", all.getJSONArray("nodes").getJSONObject(2).getString("text"))
        assertTrue(all.getBoolean("include_unimportant"))
    }
    @Test fun requestedPackageAndInvalidBackendCannotSilentlyChangeReadingMethod() {
        assertThrows(IllegalArgumentException::class.java) {
            PhoneExecution.validateObservation(JSONObject().put("backend", "invalid"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PhoneExecution.validateObservation(JSONObject().put("backend", "accessibility").put("privilege_backend", "root"))
        }
        assertThrows(PhoneToolError::class.java) {
            PhoneUiDump.parse("""<hierarchy><node package="other.app" bounds="[0,0][1,1]"/></hierarchy>""",
                20, JSONObject().put("package_name", "target.app"), screen, "root")
        }
    }
    @Test fun accessibilityCanRetainUnimportantTextWithoutExposingEmptyLayoutNoise() {
        val service = Robolectric.buildService(PhoneAccessibility::class.java).create().get()
        val root = AccessibilityNodeInfo.obtain().apply {
            packageName = "target.app"; text = "普通文字"; isVisibleToUser = true; isImportantForAccessibility = true
            setBoundsInScreen(Rect(0, 0, 300, 300))
        }
        val child = AccessibilityNodeInfo.obtain().apply {
            packageName = "target.app"; text = "被系统忽略的正文"; isVisibleToUser = true; isImportantForAccessibility = false
            setBoundsInScreen(Rect(10, 30, 200, 100))
        }
        shadowOf(root).addChild(child)
        val window = AccessibilityWindowInfo.obtain().apply {
            shadowOf(this).setRoot(root); shadowOf(this).setType(AccessibilityWindowInfo.TYPE_APPLICATION)
            shadowOf(this).setId(1); shadowOf(this).setFocused(true)
        }
        shadowOf(service).setWindows(listOf(window))
        PhoneAccessibility::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        try {
            assertEquals(2, service.observe(owner = "a").getJSONArray("nodes").length())
            assertEquals(1, service.observe(owner = "a", options = JSONObject().put("include_unimportant", false)).getJSONArray("nodes").length())
            val context = ApplicationProvider.getApplicationContext<Context>()
            context.deleteDatabase("phone-tools.db")
            val engine = PhoneEngine(context); engine.settings.enabled = true
            fun call(tool: String, args: JSONObject, id: String) = engine.invoke(tool, args, id, agentId = "test", agentName = "Transport")
            assertFalse(call("phone_begin_ui", JSONObject().put("client_name", "Codex 测试"), "begin").getBoolean("isError"))
            call("phone_get_capabilities", JSONObject(), "cap")
            assertEquals("Codex 测试", engine.connectionStates().single().name)
            val readOnly = call("phone_tap", JSONObject().put("node_id", "p41").put("snapshot_id", "uiautomator-only"), "readonly")
            assertEquals("read_only_backend", readOnly.getJSONObject("structuredContent").getString("code"))
            assertEquals("not_started", readOnly.getJSONObject("structuredContent").getString("execution"))
            val unavailable = call("phone_observe", JSONObject().put("backend", "uiautomator"), "explicit")
            assertTrue(unavailable.getBoolean("isError"))
            assertEquals("backend_unavailable", unavailable.getJSONObject("structuredContent").getString("code"))
            engine.disconnect("test")
        } finally { service.onDestroy() }
    }
}
