package com.chuckiehelper.mobile.telemetry

import android.app.Activity
import android.app.Application
import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import android.os.Looper
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AppUsageTelemetryTest {
    private class Endpoint(var accepted: String = "true") : AutoCloseable {
        private val socket = ServerSocket(0)
        val requests = LinkedBlockingQueue<JSONObject>()
        val url = "http://127.0.0.1:${socket.localPort}/report"
        private val worker = thread(isDaemon = true) {
            try { while (!socket.isClosed) socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                val data = CharArray(length)
                var offset = 0
                while (offset < length) { val n = reader.read(data, offset, length - offset); if (n < 0) break; offset += n }
                val body = "{\"accepted\":$accepted}".toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(body); flush()
                }
                requests.put(JSONObject(String(data)))
            } } catch (_: java.io.IOException) { }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }

    private fun waitUntil(predicate: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < end) Thread.sleep(20)
        assertTrue(predicate())
    }

    @Test fun failedAcknowledgementPersistsAndProcessRestartBackfillsSameInstallation() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("usage-telemetry", Context.MODE_PRIVATE)
        val store = UsageTelemetryStore(prefs)
        Endpoint("\"true\"").use { endpoint ->
            val first = AppUsageTelemetry(app, endpoint.url)
            try {
                first.onActivityStarted(Robolectric.buildActivity(Activity::class.java).get())
                val report = endpoint.requests.poll(5, TimeUnit.SECONDS)!!
                assertEquals(1, report.getInt("schemaVersion"))
                assertEquals(com.chuckiehelper.mobile.BuildConfig.APPLICATION_ID, report.getString("packageName"))
                assertEquals(setOf("manufacturer", "brand", "model", "androidVersion", "sdkInt"), report.getJSONObject("device").keys().asSequence().toSet())
                assertFalse(report.has("accountId"))
                waitUntil { store.pending() != null }
                val id = report.getString("installationId")
                first.close()
                endpoint.accepted = "true"
                val restarted = AppUsageTelemetry(app, endpoint.url)
                try {
                    restarted.onActivityStarted(Robolectric.buildActivity(Activity::class.java).get())
                    val retry = endpoint.requests.poll(5, TimeUnit.SECONDS)!!
                    assertEquals(id, retry.getString("installationId"))
                    waitUntil { store.pending() == null }
                } finally { restarted.close() }
            } finally { first.close() }
        }
    }

    @Test fun optOutBlocksFirstReportAndEnablingBackfillsInstallation() {
        val app = RuntimeEnvironment.getApplication()
        val settings = app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        settings.edit().putBoolean(USAGE_STATS_KEY, false).commit()
        Endpoint().use { endpoint ->
            val telemetry = AppUsageTelemetry(app, endpoint.url)
            try {
                telemetry.onActivityStarted(Robolectric.buildActivity(Activity::class.java).get())
                assertNull(endpoint.requests.poll(250, TimeUnit.MILLISECONDS))
                settings.edit().putBoolean(USAGE_STATS_KEY, true).commit()
                shadowOf(Looper.getMainLooper()).idle()
                assertNotNull(endpoint.requests.poll(5, TimeUnit.SECONDS))
            } finally { telemetry.close() }
        }
    }
}
