package com.chuckiehelper.mobile.phonecontrol

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneObservationTest {
    private lateinit var service: PhoneAccessibility
    private lateinit var root: AccessibilityNodeInfo
    private lateinit var window: AccessibilityWindowInfo
    private lateinit var engine: PhoneEngine
    private val clicks = AtomicInteger()
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("phone-tools.db")
        context.getSharedPreferences("phone-tools", 0).edit().clear().commit()
        service = Robolectric.buildService(PhoneAccessibility::class.java).create().get()
        PhoneAccessibility::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service)
        root = node("点击", true)
        shadowOf(root).setOnPerformActionListener { _, _ -> clicks.incrementAndGet(); true }
        window = window(root, 1, AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(service).setWindows(listOf(window))
        engine = PhoneEngine(context); engine.settings.enabled = true
    }
    @After fun cleanup() { service.onDestroy() }
    private fun node(text: String, clickable: Boolean = false) = AccessibilityNodeInfo.obtain().apply {
        this.text = text; packageName = "target.app"; isVisibleToUser = true; isEnabled = true; isClickable = clickable
        setBoundsInScreen(Rect(10, 30, 100, 100)); shadowOf(this).setRefreshReturnValue(true)
    }
    private fun window(root: AccessibilityNodeInfo, id: Int, type: Int) = AccessibilityWindowInfo.obtain().apply {
        shadowOf(this).setRoot(root); shadowOf(this).setId(id); shadowOf(this).setType(type)
        shadowOf(this).setLayer(id); shadowOf(this).setFocused(true)
        shadowOf(root).setAccessibilityWindowInfo(this)
    }
    @Test fun applicationWindowWinsOverFocusedOverlayAndSystemNoise() {
        val overlay = node("Agent overlay").apply { packageName = "com.chuckiehelper.mobile" }
        shadowOf(service).setWindows(listOf(window(overlay, 9, AccessibilityWindowInfo.TYPE_SYSTEM), window))
        val result = service.observe(owner = "a")
        assertEquals("target.app", result.getString("package"))
        assertEquals(1, result.getJSONArray("nodes").length())
        assertFalse(result.toString().contains("Agent overlay"))
        assertTrue(service.observe(owner = "a", options = JSONObject().put("include_system", true)).toString().contains("Agent overlay"))
    }
    @Test fun filteredObservationProjectionAndDeltaPreserveFreshOwnership() {
        val child = node("价格5999")
        shadowOf(child).setAccessibilityWindowInfo(window); shadowOf(root).addChild(child)
        val options = JSONObject().put("mode", "compact").put("filter", JSONObject().put("text_contains", "价格"))
        val first = service.observe(owner = "a", options = options)
        assertEquals(1, first.getJSONArray("nodes").length())
        val next = service.observe(owner = "a", options = JSONObject(options.toString()).put("changed_since", first.getString("snapshot_id")))
        assertTrue(next.getBoolean("delta")); assertEquals(0, next.getJSONArray("nodes").length())
        val other = service.observe(owner = "b", options = JSONObject(options.toString()).put("changed_since", first.getString("snapshot_id")))
        assertTrue(other.getBoolean("reset")); assertFalse(other.getBoolean("delta"))
        assertThrows(PhoneToolError::class.java) { service.node("n1", first.getString("snapshot_id"), "b") }
    }
    @Test fun invalidRectanglesAreDiagnosticOnlyAndCannotBeClicked() {
        root.setBoundsInScreen(Rect(100,30,10,100))
        assertEquals(0, service.observe(owner = "a").getJSONArray("nodes").length())
        val raw = service.observe(owner = "a", options = JSONObject().put("mode", "raw"))
        val row = raw.getJSONArray("nodes").getJSONObject(0)
        assertFalse(row.getBoolean("bounds_valid")); assertFalse(row.has("tap_point"))
        assertEquals("target_not_visible", assertThrows(PhoneToolError::class.java) {
            service.tap(JSONObject().put("node_id", row.getString("node_id")).put("snapshot_id", raw.getString("snapshot_id")), "a")
        }.code)
        assertEquals(0, clicks.get())
    }
    @Test fun compositeActionObservesAndChecksPostconditionWithoutExtraClientCall() {
        engine.invoke("phone_begin_ui", JSONObject(), "begin", agentId = "a")
        val args = JSONObject().put("selector", JSONObject().put("text", "点击"))
            .put("guard", JSONObject().put("package_name", "target.app"))
            .put("wait_for", JSONObject().put("selector", JSONObject().put("text", "点击")).put("require_change", false))
            .put("return_state", "compact")
        val result = engine.invoke("phone_tap", args, "tap", agentId = "a")
        assertFalse(result.toString(), result.getBoolean("isError"))
        assertTrue(result.getJSONObject("structuredContent").getBoolean("matched"))
        assertTrue(result.getJSONObject("structuredContent").has("state"))
        assertEquals(1, clicks.get())
        assertEquals(result.toString(), engine.invoke("phone_tap", args, "tap", agentId = "a").toString())
        assertEquals(1, clicks.get())
    }
    @Test fun actionWaitCannotMatchUnchangedPreActionScreen() {
        engine.invoke("phone_begin_ui", JSONObject(), "begin", agentId = "a")
        val args = JSONObject().put("selector", JSONObject().put("text", "点击"))
            .put("wait_for", JSONObject().put("text_regex", "点击|WLAN").put("timeout_ms", 10))
        val result = engine.invoke("phone_tap", args, "unchanged", agentId = "a")
        val data = result.getJSONObject("structuredContent")
        assertTrue(result.getBoolean("isError"))
        assertEquals("postcondition_timeout", data.getString("code"))
        assertFalse(data.getJSONObject("state").getBoolean("matched"))
        assertFalse(data.getJSONObject("state").getBoolean("state_changed"))
        assertEquals(data.getJSONObject("state_before").getString("signature"), data.getJSONObject("state").getString("signature"))
        assertEquals(1, clicks.get())
    }
    @Test fun actionWaitReturnsNewWindowEvenWhenTextAlreadyMatched() {
        val next = window(node("点击 WLAN 已连接"), 7, AccessibilityWindowInfo.TYPE_APPLICATION)
        shadowOf(root).setOnPerformActionListener { _, _ ->
            clicks.incrementAndGet(); shadowOf(service).setWindows(listOf(next)); true
        }
        engine.invoke("phone_begin_ui", JSONObject(), "begin", agentId = "a")
        val result = engine.invoke("phone_tap", JSONObject().put("selector", JSONObject().put("text", "点击"))
            .put("wait_for", JSONObject().put("text_regex", "点击|WLAN")), "changed", agentId = "a")
        assertFalse(result.toString(), result.getBoolean("isError"))
        val data = result.getJSONObject("structuredContent")
        assertEquals(1, data.getJSONObject("state_before").getInt("window_id"))
        assertEquals(7, data.getJSONObject("state").getInt("window_id"))
        assertTrue(data.getJSONObject("state").getBoolean("state_changed"))
    }
    @Test fun revisionsRemainMonotonicAcrossWindowsButDoNotProveContentChange() {
        val first = service.observe(owner = "a")
        shadowOf(service).setWindows(listOf(window(node("其他页面"), 7, AccessibilityWindowInfo.TYPE_APPLICATION)))
        val second = service.observe(owner = "a")
        assertTrue(second.getLong("revision") > first.getLong("revision"))
        assertEquals(first.getString("revision_epoch"), second.getString("revision_epoch"))
        assertTrue(PhoneExecution.changed(first, second))
        val third = service.observe(owner = "a")
        assertTrue(third.getLong("revision") > second.getLong("revision"))
        assertFalse(PhoneExecution.changed(second, third))
    }
    @Test fun observationBudgetsReportNormalizationWithoutIntegerOverflow() {
        engine.invoke("phone_begin_ui", JSONObject(), "begin", agentId = "a")
        for ((index, requested) in listOf(1L, 300L, 600L, 1200L, Long.MAX_VALUE, 0L, -2L).withIndex()) {
            val result = engine.invoke("phone_observe", JSONObject().put("limit", requested), "budget-$index", agentId = "a")
            assertFalse(result.toString(), result.getBoolean("isError"))
            val data = result.getJSONObject("structuredContent")
            assertEquals(requested, data.getLong("requested_limit"))
            assertEquals(requested.coerceIn(1, 600).toInt(), data.getInt("effective_limit"))
        }
        val found = engine.invoke("phone_find_nodes", JSONObject().put("text", "点击").put("limit", 1200), "find-budget", agentId = "a")
            .getJSONObject("structuredContent")
        assertEquals(1200, found.getInt("requested_limit")); assertEquals(600, found.getInt("effective_limit"))
        val default = engine.invoke("phone_observe", JSONObject(), "default-budget", agentId = "a").getJSONObject("structuredContent")
        assertEquals(400, default.getInt("requested_limit")); assertEquals(400, default.getInt("effective_limit"))
    }
    @Test fun stateChangeDetectsRotationAndPackageEvenWithReusedWindowAndSignature() {
        val before = JSONObject().put("window_id", 1).put("signature", "same").put("rotation", 0)
            .put("package", "one.app").put("foreground_package", "one.app")
        for ((key, value) in listOf("rotation" to 90, "package" to "two.app", "foreground_package" to "two.app")) {
            val after = JSONObject(before.toString()).put(key, value)
            assertTrue(PhoneExecution.changed(before, after))
            assertEquals(value, PhoneExecution.stateMarker(after).get(key))
        }
        assertFalse(PhoneExecution.changed(before, JSONObject(before.toString()).put("revision", 99)))
    }
    @Test fun mismatchedGuardAndAmbiguousSelectorDoNotClick() {
        engine.invoke("phone_begin_ui", JSONObject(), "begin", agentId = "a")
        val args = JSONObject().put("selector", JSONObject().put("text", "点击")).put("guard", JSONObject().put("package_name", "wrong.app"))
        val failed = engine.invoke("phone_tap", args, "guard-failed", agentId = "a").getJSONObject("structuredContent")
        assertEquals("precondition_failed", failed.getString("code")); assertEquals("not_started", failed.getString("execution"))
        val recorded = engine.status("guard-failed")
        assertEquals("completed", recorded.getString("state"))
        assertTrue(recorded.isNull("started_at")); assertFalse(recorded.isNull("finished_at"))
        val duplicate = node("点击", true)
        shadowOf(duplicate).setAccessibilityWindowInfo(window); shadowOf(root).addChild(duplicate)
        val ambiguous = engine.invoke("phone_tap", JSONObject().put("selector", JSONObject().put("text", "点击")), "ambiguous", agentId = "a")
        assertEquals("ambiguous_target", ambiguous.getJSONObject("structuredContent").getString("code"))
        assertEquals(0, clicks.get())
    }
    @Test fun batchWaitTimeoutDoesNotRunFollowingClick() {
        engine.invoke("phone_begin_ui", JSONObject(), "begin", agentId = "a")
        val steps = org.json.JSONArray()
            .put(JSONObject().put("tool", "phone_wait").put("arguments", JSONObject().put("text", "missing").put("timeout_ms", 1)))
            .put(JSONObject().put("tool", "phone_tap").put("arguments", JSONObject().put("selector", JSONObject().put("text", "点击"))))
        val result = engine.invoke("phone_execute", JSONObject().put("steps", steps).put("return_state", "none"), "timeout-batch", agentId = "a")
        assertTrue(result.toString(), result.getBoolean("isError"))
        val receipt = result.getJSONObject("structuredContent")
        assertEquals("postcondition_timeout", receipt.getString("code"))
        assertEquals(0, receipt.getInt("completed_steps"))
        assertEquals(1, receipt.getJSONArray("steps").length())
        assertEquals(0, clicks.get())
    }
}
