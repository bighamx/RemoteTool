package com.chuckiehelper.mobile.phonecontrol

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneHttpRecoveryTest {
    @Test fun realHttpRecoveryPreservesNoticesAndRejectsOldTransportAndBlockedOwner() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("phone-tools.db")
        val engine = PhoneEngine(context); engine.settings.enabled = true
        engine.settings.port = java.net.ServerSocket(0).use { it.localPort }
        val server = PhoneHttpServer(engine)
        server.start(15000, false)
        fun rpc(method: String, params: JSONObject = JSONObject(), session: String? = null): Triple<Int, String?, JSONObject> {
            val connection = URL("http://127.0.0.1:${engine.settings.port}/mcp").openConnection() as HttpURLConnection
            connection.requestMethod = "POST"; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer ${engine.settings.token}")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json, text/event-stream")
            if (session != null) connection.setRequestProperty("Mcp-Session-Id", session)
            connection.outputStream.use { it.write(JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params).toString().toByteArray()) }
            val status = connection.responseCode
            val body = (if (status >= 400) connection.errorStream else connection.inputStream).bufferedReader().use { JSONObject(it.readText()) }
            return Triple(status, connection.getHeaderField("Mcp-Session-Id"), body).also { connection.disconnect() }
        }
        try {
            val first = rpc("initialize")
            val old = first.second!!
            val secret = first.third.getJSONObject("result").getJSONObject("_meta").getJSONObject("remotetool").getString("resume_token")
            val notice = rpc("tools/call", JSONObject().put("name", "phone_notify_user").put("arguments", JSONObject().put("message", "Test").put("kind", "help")), old)
                .third.getJSONObject("result").getJSONObject("structuredContent").getString("notice_id")
            val resumed = rpc("initialize", JSONObject().put("_meta", JSONObject().put("remotetool/resume_token", secret)))
            assertNotEquals(old, resumed.second)
            assertEquals(404, rpc("ping", session = old).first)
            val reply = rpc("tools/call", JSONObject().put("name", "phone_get_user_reply").put("arguments", JSONObject().put("notice_id", notice)), resumed.second)
            assertEquals("pending", reply.third.getJSONObject("result").getJSONObject("structuredContent").getString("status"))
            engine.disconnect("direct:$old", byUser = true)
            val blocked = rpc("initialize", JSONObject().put("_meta", JSONObject().put("remotetool/resume_token", secret)))
            assertEquals("connection_closed_by_user", blocked.third.getJSONObject("error").getJSONObject("data").getString("code"))
            assertNull(blocked.second)
            assertEquals(-32602, rpc("initialize", JSONObject().put("_meta", JSONObject().put("remotetool/resume_token", ""))).third.getJSONObject("error").getInt("code"))
        } finally { server.stop(); engine.stop() }
    }
}
