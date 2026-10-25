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
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneToolsTest {
    private lateinit var context: Context
    private lateinit var engine: PhoneEngine
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("phone-tools", 0).edit().clear().commit()
        context.deleteDatabase("phone-tools.db")
        engine = PhoneEngine(context); engine.settings.enabled = true
    }
    @Test fun advancedDeniedBeforeShellRuns() {
        val result = engine.invoke("phone_shell", JSONObject().put("command", "echo should-not-run"), "blocked")
        assertEquals("advanced_disabled", result.getJSONObject("structuredContent").getString("code"))
    }
    @Test fun duplicateFileUploadReturnsReceiptAndConflictingBodyIsRejected() {
        val file = "test-${java.util.UUID.randomUUID()}.txt"
        val args = JSONObject().put("action", "upload").put("name", file).put("data_base64", "aGVsbG8=")
        val first = engine.invoke("phone_transfer_file", args, "upload-1")
        assertFalse(first.getBoolean("isError"))
        assertEquals(first.toString(), engine.invoke("phone_transfer_file", JSONObject(args.toString()), "upload-1").toString())
        val conflict = engine.invoke("phone_transfer_file", JSONObject(args.toString()).put("data_base64", "d29ybGQ="), "upload-1")
        assertEquals("request_conflict", conflict.getJSONObject("structuredContent").getString("code"))
        val fetched = engine.invoke("phone_transfer_file", JSONObject().put("action", "download").put("name", file), "download-1")
        assertEquals("aGVsbG8=", fetched.getJSONObject("structuredContent").getString("data_base64"))
    }
    @Test fun interruptedExecutionIsNotReplayedAfterRestart() {
        val journal = PhoneJournal(context); journal.begin("interrupted", "digest"); journal.close()
        val restarted = PhoneJournal(context)
        assertEquals("unknown", restarted.status("interrupted").getString("state"))
        try { restarted.existing("interrupted", "digest"); fail("must not replay") }
        catch (e: PhoneToolError) { assertEquals("delivery_unknown", e.code) }
    }
    @Test fun disconnectCancelsOwnPendingHelpWithoutStoppingTheService() {
        val help = engine.invoke("phone_notify_user", JSONObject().put("message", "测试协助").put("kind", "help"), "help-before-disconnect").getJSONObject("structuredContent")
        val reply = engine.invoke("phone_disconnect", JSONObject(), "disconnect-now").getJSONObject("structuredContent")
        assertTrue(reply.getBoolean("disconnected"))
        assertFalse(reply.getBoolean("service_stopped"))
        assertTrue(engine.settings.enabled)
        assertEquals("cancelled", engine.agentUi.result("direct", help.getString("notice_id")).getString("status"))
    }
    @Test fun explanationsAreOptionalValidatedMetadataAndDoNotChangeDeliveryIdentity() {
        val file = "explanation-${java.util.UUID.randomUUID()}.txt"
        val args = JSONObject().put("action", "upload").put("name", file).put("data_base64", "aGVsbG8=").put("explanation", "上传测试文件")
        val first = engine.invoke("phone_transfer_file", args, "explained-upload")
        assertFalse(first.getBoolean("isError"))
        val retry = engine.invoke("phone_transfer_file", JSONObject(args.toString()).put("explanation", "核对同一上传请求"), "explained-upload")
        assertEquals(first.toString(), retry.toString())
        assertEquals("上传测试文件", args.getString("explanation"))
        val downloaded = engine.invoke("phone_transfer_file", JSONObject().put("action", "download").put("name", file), "explained-download")
        assertEquals("aGVsbG8=", downloaded.getJSONObject("structuredContent").getString("data_base64"))
        for (bad in listOf<Any>(123, "字".repeat(241))) {
            val result = engine.invoke("phone_get_capabilities", JSONObject().put("explanation", bad), "bad-explanation-${bad.hashCode()}")
            assertEquals("invalid_arguments", result.getJSONObject("structuredContent").getString("code"))
        }
        val catalog = PhoneToolCatalog.tools(true)
        for (i in 0 until catalog.length()) {
            val schema = catalog.getJSONObject(i).getJSONObject("inputSchema")
            assertEquals("string", schema.getJSONObject("properties").getJSONObject("explanation").getString("type"))
            assertFalse((0 until schema.getJSONArray("required").length()).any { schema.getJSONArray("required").getString(it) == "explanation" })
        }
    }
    @Test fun stoppedEngineRejectsRequests() {
        engine.stop()
        assertEquals("disabled", engine.invoke("phone_get_capabilities", JSONObject(), "after-stop").getJSONObject("structuredContent").getString("code"))
    }
    @Test fun pathTraversalAndUnknownArgumentsRejected() {
        val result = engine.invoke("phone_transfer_file", JSONObject().put("action", "upload").put("name", "../escape").put("data_base64", ""), "traversal")
        assertEquals("invalid_arguments", result.getJSONObject("structuredContent").getString("code"))
        assertTrue(engine.invoke("phone_tap", JSONObject().put("unknown", true), "bad-arg").getBoolean("isError"))
    }
    @Test fun mcpNegotiatesAndDoesNotConflateJsonRpcIdsAcrossClients() {
        val keys = mutableListOf<String>()
        val protocol = PhoneMcp({ PhoneToolCatalog.tools(it) }, { _, _, id, _ -> keys.add(id); JSONObject().put("content", org.json.JSONArray()) })
        val initialize = protocol.handle(JSONObject("""{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-11-25"}}"""))!!
        assertEquals("2025-11-25", initialize.getJSONObject("result").getString("protocolVersion"))
        val call = JSONObject("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"phone_get_capabilities","arguments":{}}}""")
        protocol.handle(call); protocol.handle(call)
        assertNotEquals(keys[0], keys[1])
        assertNull(protocol.handle(JSONObject("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")))
    }
    @Test fun standaloneHttpAuthenticatesAndExecutesWithoutWebConnection() {
        engine.settings.port = java.net.ServerSocket(0).use { it.localPort }
        val server = PhoneHttpServer(engine); server.start(3000, true)
        fun request(path: String, auth: Boolean, body: String? = null): Pair<Int, String> {
            val conn = URL("http://127.0.0.1:${engine.settings.port}$path").openConnection() as HttpURLConnection
            conn.connectTimeout = 3000; conn.readTimeout = 5000
            if (auth) conn.setRequestProperty("Authorization", "Bearer ${engine.settings.token}")
            if (body != null) { conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("Accept", "application/json, text/event-stream"); conn.outputStream.use { it.write(body.toByteArray()) } }
            return try { val status = conn.responseCode; status to (if (status >= 400) conn.errorStream else conn.inputStream).bufferedReader().use { it.readText() } } finally { conn.disconnect() }
        }
        try {
            assertEquals(401, request("/api/phone/v1/devices", false).first)
            assertEquals(200, request("/api/phone/v1/devices", true).first)
            val result = request("/mcp", true, """{"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"phone_get_capabilities","arguments":{}}}""")
            assertEquals(200, result.first)
            assertFalse(JSONObject(result.second).getJSONObject("result").getBoolean("isError"))
            val chinese = "中文说明与草稿🙂：不要变成替换字符"
            val params = JSONObject().put("name", "phone_notify_user").put("arguments", JSONObject().put("message", chinese))
            val unicodeMcp = request("/mcp", true, JSONObject().put("jsonrpc", "2.0").put("id", 8)
                .put("method", "tools/call").put("params", params).toString())
            assertEquals(chinese, JSONObject(unicodeMcp.second).getJSONObject("result").getJSONObject("structuredContent").getString("message"))
            val unicodeRest = request("/api/phone/v1/invocations", true, JSONObject().put("tool", "phone_notify_user")
                .put("request_id", "utf8-rest").put("arguments", JSONObject().put("message", chinese)).toString())
            assertEquals(chinese, JSONObject(unicodeRest.second).getJSONObject("structuredContent").getString("message"))
        } finally { server.stop() }
    }
    @Test fun exportAuthoritativeCatalogForAgentAdapters() {
        val catalog = PhoneToolCatalog.tools(true)
        assertTrue(catalog.length() >= 20)
        assertTrue(PhoneToolCatalog.tools(false).length() < catalog.length())
        for (i in 0 until catalog.length()) assertFalse(catalog.getJSONObject(i).getJSONObject("inputSchema").getBoolean("additionalProperties"))
        java.io.File("build/phone-tools-catalog.json").apply { parentFile!!.mkdirs(); writeText(catalog.toString(2)) }
        val shipped = java.io.File("../../tools/PhoneTools/hermes_plugin/tools.json")
        if (shipped.isFile) assertEquals("Regenerate shipped Hermes schemas from build/phone-tools-catalog.json", catalog.toString(), org.json.JSONArray(shipped.readText()).toString())
    }
    @Test fun assistanceRequestsDoNotLeakAcrossAgentBindingsOrRequestReceipts() {
        val args = JSONObject().put("message", "请重开主 App").put("kind", "help")
        val created = engine.invoke("phone_notify_user", args, "help-owner", agentId = "web:a")
        val id = created.getJSONObject("structuredContent").getString("notice_id")
        val own = engine.invoke("phone_get_user_reply", JSONObject().put("notice_id", id), "read-own", agentId = "web:a")
        assertEquals("pending", own.getJSONObject("structuredContent").getString("status"))
        val other = engine.invoke("phone_get_user_reply", JSONObject().put("notice_id", id), "read-other", agentId = "web:b")
        assertEquals("not_found", other.getJSONObject("structuredContent").getString("status"))
        val collision = engine.invoke("phone_notify_user", args, "help-owner", agentId = "web:b")
        assertTrue(collision.getBoolean("isError"))
        assertEquals("request_conflict", collision.getJSONObject("structuredContent").getString("code"))
    }
    @Test fun assistanceStaysResponsiveWhileADeviceOperationIsBlocked() {
        val lock = PhoneEngine::class.java.getDeclaredField("control").apply { isAccessible = true }
            .get(engine) as java.util.concurrent.locks.ReentrantLock
        val acquired = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.submit { lock.lock(); try { acquired.countDown(); release.await() } finally { lock.unlock() } }
        try {
            assertTrue(acquired.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val help = engine.invoke("phone_notify_user", JSONObject().put("message", "请帮忙返回页面").put("kind", "help"), "responsive-help")
            assertFalse(help.getBoolean("isError"))
            val id = help.getJSONObject("structuredContent").getString("notice_id")
            val reply = engine.invoke("phone_get_user_reply", JSONObject().put("notice_id", id), "responsive-reply")
            assertEquals("pending", reply.getJSONObject("structuredContent").getString("status"))
            val blocked = engine.invoke("phone_transfer_file", JSONObject().put("action", "list"), "busy-device")
            assertEquals("device_busy", blocked.getJSONObject("structuredContent").getString("code"))
        } finally { release.countDown(); executor.shutdownNow() }
    }
}
