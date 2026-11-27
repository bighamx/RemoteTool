package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AgentAttachmentRecoveryTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("Attachment reconciliation did not settle", condition())
    }

    private fun missingAttachment(status: Int, partial: Boolean = false) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        val binds = AtomicInteger()
        val responder = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    var remaining = 0
                    while (true) {
                        val header = reader.readLine().orEmpty()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) remaining = header.substringAfter(':').trim().toInt()
                    }
                    val input = StringBuilder()
                    while (remaining > 0) {
                        val buffer = CharArray(remaining)
                        val count = reader.read(buffer)
                        if (count < 0) break
                        input.append(buffer, 0, count)
                        remaining -= count
                    }
                    val binding = request.contains("/attachments ")
                    if (binding) binds.incrementAndGet()
                    val missing = binding && input.contains("missing-upload")
                    val catalog = JSONArray().apply { if (partial) put(obj("id" to "existing-upload", "name" to "kept.png")) }
                    val body = (if (missing) obj("code" to if (status == 404) "attachment_missing" else "", "message" to "missing")
                        else if (binding) obj("success" to true) else obj("data" to catalog)).toString().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 ${if (missing) status else 200} Result\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        val app = RuntimeEnvironment.getApplication()
        val device = "binding-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences("codex-$device", 0)
        val attachment = obj("id" to "missing-upload", "name" to "old.png")
        val files = JSONArray().put(attachment).apply { if (partial) put(obj("id" to "existing-upload", "name" to "kept.png")) }
        prefs.edit().putString("session", "s").putString("steeringMessages", JSONArray().put(obj(
            "key" to "confirmed-steering-key", "session" to "s", "text" to "retained message",
            "existingIds" to JSONArray(), "anchor" to 0, "delivery" to "已送达",
            "timestamp" to 1795700000000L, "attachments" to files
        )).toString()).commit()
        val model = HermesModel(app, device, "codex")
        val store = ViewModelStore().apply { put("model", model) }
        HermesModel::class.java.getDeclaredField("api").apply { isAccessible = true }
            .set(model, NativeApi("http://127.0.0.1:${server.localPort}"))
        val history = obj("data" to JSONArray().put(obj("id" to 1, "role" to "user", "content" to "retained message",
            "timestamp" to 1795700001000L, "request_key" to "confirmed-steering-key", "attachments" to files)))
        fun refresh() {
            val method = HermesModel::class.java.getDeclaredMethod("loadHistoryNow", String::class.java,
                Boolean::class.javaPrimitiveType, JSONObject::class.java, JSONObject::class.java, Continuation::class.java).apply { isAccessible = true }
            val job = CoroutineScope(Dispatchers.Main).launch {
                suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                    method.invoke(model, "s", false, history, obj("data" to JSONArray()), continuation)
                }
            }
            await { job.isCompleted }
        }
        try {
            refresh()
            val expected = if (partial) 2 else 1
            await { binds.get() == expected && prefs.getString("steeringMessages", "[]") == "[]" }
            refresh()
            assertEquals("A confirmed missing binding must not be retried on every history read", expected, binds.get())
            assertEquals("retained message", model.messages.first { it.serverId == 1L }.text)
            assertNull("A missing historical file must not show an error banner", model.error)
        } finally {
            store.clear()
            server.close()
            responder.join(1000)
            shadowOf(Looper.getMainLooper()).idle()
            prefs.edit().clear().commit()
        }
    }

    @Test fun typedMissingFileIsRetiredSilentlyThroughHistoryReconciliation() = missingAttachment(404)
    @Test fun legacy500IsRetiredOnlyAfterReadingTheServerFileCatalog() = missingAttachment(500)
    @Test fun partiallyMissingUploadsKeepTheirSurvivingAttachments() = missingAttachment(404, partial = true)
}
