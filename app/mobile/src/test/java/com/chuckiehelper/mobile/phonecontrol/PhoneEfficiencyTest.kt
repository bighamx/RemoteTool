package com.chuckiehelper.mobile.phonecontrol

import android.content.Context
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneEfficiencyTest {
    private lateinit var context: Context
    private lateinit var engine: PhoneEngine
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase("phone-tools.db"); context.deleteDatabase("phone-connections.db")
        context.getSharedPreferences("phone-tools", 0).edit().clear().commit()
        engine = PhoneEngine(context); engine.settings.enabled = true
    }
    private fun row(text: String, bounds: Boolean = true) = JSONObject().put("node_id", text).put("text", text)
        .put("visible", true).put("bounds_valid", bounds).put("description", "").put("resource_id", "input")
    private fun state(vararg rows: JSONObject) = JSONObject().put("nodes", JSONArray(rows.toList())).put("package", "target").put("foreground_package", "target")
    @Test fun capabilitiesIdentifyTheInstalledAppBuild() {
        val app = engine.capabilities().getJSONObject("app")
        assertEquals(com.chuckiehelper.mobile.BuildConfig.APPLICATION_ID, app.getString("package_name"))
        assertEquals(com.chuckiehelper.mobile.BuildConfig.VERSION_NAME, app.getString("version_name"))
        assertEquals(com.chuckiehelper.mobile.BuildConfig.VERSION_CODE, app.getInt("version_code"))
    }
    @Test fun invalidAndOffscreenRectanglesNeverBecomeClickPoints() {
        val screen = Rect(0, 0, 1080, 2400)
        for (bad in listOf(Rect(1175,476,1080,637), Rect(28,2387,1080,2344), Rect(0,2174,1080,2174), Rect(0,-30,100,-1)))
            assertNull(PhoneScreen.visible(bad, screen))
        assertEquals(Rect(0,0,100,20), PhoneScreen.visible(Rect(-5,-20,100,20), screen))
    }
    @Test fun selectorsRejectAmbiguityAndInvalidGeometry() {
        val selector = JSONObject().put("resource_id", "input")
        assertThrows(PhoneToolError::class.java) { PhoneSelectors.unique(state(row("a"), row("b")), selector) }
        assertThrows(PhoneToolError::class.java) { PhoneSelectors.unique(state(row("a", false)), selector) }
        assertEquals("a", PhoneSelectors.unique(state(row("a")), selector).getString("node_id"))
    }
    @Test fun clickPointsAvoidOverlaysAndDoNotExistWhenFullyObscured() {
        val area = Rect(0, 0, 100, 100)
        assertEquals(Rect(0, 50, 100, 100), PhoneScreen.tapArea(area, listOf(Rect(0, 0, 100, 50))))
        assertNull(PhoneScreen.tapArea(area, listOf(Rect(0, 0, 100, 100))))
        assertEquals(area, PhoneScreen.tapArea(area, listOf(Rect(101, 0, 200, 100))))
    }
    @Test fun allBatchCoordinatesAreCheckedBeforeAnyStepExecutes() {
        assertThrows(Exception::class.java) { PhoneExecution.validateCoordinates("phone_tap", JSONObject().put("x", 20).put("y", 2400), 1080, 2400) }
        assertThrows(Exception::class.java) { PhoneExecution.validateCoordinates("phone_swipe", JSONObject().put("x1", 20).put("y1", 20).put("x2", -1).put("y2", 200), 1080, 2400) }
        val steps = JSONArray().put(JSONObject().put("tool", "phone_get_capabilities"))
            .put(JSONObject().put("tool", "phone_tap").put("arguments", JSONObject().put("x", -1).put("y", 20)))
        val result = engine.invoke("phone_execute", JSONObject().put("steps", steps), "bad-coordinates")
        assertEquals("invalid_arguments", result.getJSONObject("structuredContent").getString("code"))
        assertEquals("not_started", result.getJSONObject("structuredContent").getString("execution"))
    }
    @Test fun waitUsesTextFieldsAndRealForegroundNotJsonKeysOrOverlay() {
        assertFalse(PhoneExecution.matched(state(row("price")), JSONObject().put("text", "resource_id")))
        assertTrue(PhoneExecution.matched(state(row("小米18 Pro")), JSONObject().put("text_regex", "小米18\\s*Pro")))
        assertFalse(PhoneExecution.matched(state(row("a")).put("foreground_package", "other"), JSONObject().put("package_name", "target")))
        assertTrue(PhoneExecution.matched(state(row("a")), JSONObject().put("selector", JSONObject().put("text", "missing")).put("absent", true)))
    }
    @Test fun waitConditionsAreValidatedBeforeAnyUiWork() {
        for (bad in listOf(JSONObject(), JSONObject().put("stable_ms", -1), JSONObject().put("text_regex", "["), JSONObject().put("absent", true), JSONObject().put("text", "x").put("unknown", true)))
            assertThrows(Exception::class.java) { PhoneExecution.validateWait(bad) }
    }
    @Test fun batchesValidateAllStepsAndDoNotPermitShellOrLifecycleOrRecursion() {
        for (tool in listOf("phone_shell", "phone_disconnect", "phone_begin_ui", "phone_execute", "phone_files")) {
            val steps = JSONArray().put(JSONObject().put("tool", "phone_get_capabilities"))
                .put(JSONObject().put("tool", tool).put("arguments", JSONObject()))
            assertThrows(Exception::class.java) { PhoneExecution.validateBatch(JSONObject().put("steps", steps)) }
        }
        assertThrows(Exception::class.java) { PhoneExecution.validateBatch(JSONObject().put("steps", JSONArray())) }
        assertThrows(Exception::class.java) { PhoneExecution.validateBatch(JSONObject().put("steps", JSONArray((1..21).map { JSONObject().put("tool", "phone_get_capabilities") }))) }
    }
    @Test fun batchKeepsUiOwnershipAndDuplicateResultsAndReceiptsIsolated() {
        engine.invoke("phone_begin_ui", JSONObject(), "begin", agentId = "owner")
        val args = JSONObject().put("steps", JSONArray().put(JSONObject().put("tool", "phone_get_capabilities"))).put("return_state", "none")
        val first = engine.invoke("phone_execute", args, "batch", agentId = "owner")
        assertFalse(first.toString(), first.getBoolean("isError"))
        assertEquals(1, first.getJSONObject("structuredContent").getInt("completed_steps"))
        assertEquals(first.toString(), engine.invoke("phone_execute", args, "batch", agentId = "owner").toString())
        assertFalse(engine.invoke("phone_get_operation", JSONObject().put("operation_id", "batch"), "receipt", agentId = "owner").getBoolean("isError"))
        val forbidden = engine.invoke("phone_get_operation", JSONObject().put("operation_id", "batch"), "receipt-other", agentId = "other")
        assertEquals("not_found", forbidden.getJSONObject("structuredContent").getString("code"))
        assertTrue(engine.invoke("phone_execute", args, "batch-other", agentId = "other").getBoolean("isError"))
    }
    @Test fun partialBatchStopsWithoutExecutingLaterSteps() {
        engine.invoke("phone_begin_ui", JSONObject(), "begin", agentId = "owner")
        val steps = JSONArray().put(JSONObject().put("tool", "phone_get_capabilities"))
            .put(JSONObject().put("tool", "phone_tap").put("arguments", JSONObject().put("selector", JSONObject().put("text", "not there"))))
            .put(JSONObject().put("tool", "phone_get_capabilities"))
        val result = engine.invoke("phone_execute", JSONObject().put("steps", steps).put("return_state", "none"), "partial", agentId = "owner")
        assertTrue(result.toString(), result.getBoolean("isError"))
        val data = result.getJSONObject("structuredContent")
        assertEquals(1, data.getInt("completed_steps")); assertEquals(2, data.getJSONArray("steps").length())
        assertEquals(result.toString(), engine.invoke("phone_execute", JSONObject().put("steps", steps).put("return_state", "none"), "partial", agentId = "owner").toString())
    }
}
