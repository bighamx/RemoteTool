package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
class CodexWarmupModelTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 8_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("Workspace setting did not settle", condition())
    }

    private fun fixture(status: AtomicInteger, action: (HermesModel, AtomicInteger) -> Unit) {
        val server = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        var enabled = false
        val writes = AtomicInteger()
        val responder = Thread {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    val request = reader.readLine().orEmpty()
                    var length = 0
                    while (true) {
                        val header = reader.readLine().orEmpty()
                        if (header.isEmpty()) break
                        if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toInt()
                    }
                    val chars = CharArray(length)
                    var offset = 0
                    while (offset < chars.size) { val count = reader.read(chars, offset, chars.size - offset); if (count < 0) break; offset += count }
                    if (request.startsWith("POST") && status.get() == 200) {
                        enabled = JSONObject(String(chars)).getBoolean("enabled")
                        writes.incrementAndGet()
                    }
                    val body = (if (status.get() == 200) obj("supported" to true, "enabled" to enabled) else obj("message" to "fixture unavailable")).toString().toByteArray()
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 ${status.get()} Result\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        val app = RuntimeEnvironment.getApplication()
        val device = "warmup-${UUID.randomUUID()}"
        val model = HermesModel(app, device, "codex")
        val models = ViewModelStore().apply { put("warmup", model) }
        HermesModel::class.java.getDeclaredField("api").apply { isAccessible = true }.set(model, NativeApi("http://127.0.0.1:${server.localPort}"))
        try { action(model, writes) }
        finally {
            models.clear(); server.close(); responder.join(1000); shadowOf(Looper.getMainLooper()).idle()
            app.getSharedPreferences("codex-$device", 0).edit().clear().commit()
        }
    }

    private fun read(model: HermesModel) {
        val job = CoroutineScope(Dispatchers.Main).launch { model.fetchWorkspaceWarmup() }
        await { job.isCompleted }
    }

    @Test fun settingRoundTripUsesServerStateAndPreservesItOnReadFailure() {
        val status = AtomicInteger(200)
        fixture(status) { model, writes ->
            read(model)
            assertFalse(model.workspaceWarmup!!.getBoolean("enabled"))
            model.setWorkspaceWarmup(true)
            await { !model.workspaceWarmupSaving && model.workspaceWarmup?.optBoolean("enabled") == true }
            assertEquals(1, writes.get())
            status.set(500)
            read(model)
            assertTrue(model.workspaceWarmup!!.getBoolean("enabled"))
            assertNotNull(model.workspaceWarmupError)
            assertNull(model.error)
        }
    }

    @Test fun unsupportedServerKeepsTheSettingUnavailableAndDoesNotSubmit() {
        fixture(AtomicInteger(404)) { model, writes ->
            read(model)
            model.setWorkspaceWarmup(true)
            shadowOf(Looper.getMainLooper()).idle()
            assertNull(model.workspaceWarmup)
            assertNull(model.workspaceWarmupError)
            assertEquals(0, writes.get())
        }
    }
}
