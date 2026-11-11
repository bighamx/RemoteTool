package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.os.Looper
import java.net.ServerSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import androidx.lifecycle.ViewModelStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class ClaudeWorkspaceTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (!condition() && System.nanoTime() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue("workspace did not settle", condition())
    }
    @Test fun sharedWorkspaceReadsNativeHistoryForksByUuidAndNeverReplaysAnUnknownSend() {
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        var receiptFound = false
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val row = obj("id" to "native-session", "title" to "Native", "cwd" to "/project")
        val responder = thread(isDaemon = true) { try { while (!server.isClosed) server.accept().use { socket ->
            socket.soTimeout = 5000
            val reader = socket.getInputStream().bufferedReader()
            val path = reader.readLine().split(' ')[1].substringBefore('?')
            var length = 0
            while (true) { val line = reader.readLine().orEmpty(); if (line.isEmpty()) break; if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt() }
            val chars = CharArray(length)
            var offset = 0
            while (offset < length) { val count = reader.read(chars, offset, length - offset); if (count < 0) break; offset += count }
            val body = String(chars).takeIf { it.isNotBlank() }?.let(::JSONObject) ?: JSONObject()
            requests += path to body
            var status = 200
            val response = when {
                path.endsWith("/capabilities") -> obj("protocol" to 2, "features" to obj("fork" to true, "rewind" to true, "external_session_activity" to true))
                path.endsWith("/model-options") -> obj("provider" to "anthropic", "providers" to org.json.JSONArray())
                path.endsWith("/sessions") -> obj("data" to org.json.JSONArray().put(row), "has_more" to false)
                path.endsWith("/messages") -> obj("data" to org.json.JSONArray().put(obj("id" to "native-user", "role" to "user", "content" to "hello", "timestamp" to 1794400000000L)).put(obj("id" to "tools-native-user", "role" to "system", "type" to "toolSummary", "content" to "工具 3 次")), "has_more" to false, "oldest_id" to "native-user")
                path.endsWith("/context") -> obj("context" to obj("available" to true, "tokens" to 1024, "limit" to 200000))
                path.endsWith("/active-run") -> obj("run_id" to JSONObject.NULL)
                path.endsWith("/activity") -> obj("available" to true, "running" to false)
                path.endsWith("/files") -> obj("data" to org.json.JSONArray())
                path.endsWith("/fork") -> obj("session" to obj("id" to "forked", "title" to "Fork"))
                path.endsWith("/lookup") -> if (receiptFound) obj("found" to true, "run_id" to "retained-run", "session_id" to "native-session", "status" to "running", "delivery" to "accepted") else obj("found" to false)
                path.endsWith("/runs") -> { status = 503; obj("message" to "Response lost", "delivery" to "unknown") }
                path.endsWith("/native-session") || path.endsWith("/forked") -> obj("session" to row)
                else -> { status = 404; obj("message" to "unsupported fixture endpoint") }
            }.toString().toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 $status Result\r\nContent-Type: application/json\r\nContent-Length: ${response.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(response); flush()
            }
        } } catch (_: java.io.IOException) { } }
        val application = RuntimeEnvironment.getApplication()
        val device = "claude-test-${UUID.randomUUID()}"
        val model = HermesModel(application, device, "claude")
        val store = ViewModelStore().apply { put("claude", model) }
        try {
            model.bind(NativeApi("http://127.0.0.1:${server.localPort}"))
            await { model.sessions.isNotEmpty() }
            model.select(row)
            await { model.messages.size == 2 && !model.historyLoading }
            assertEquals("Claude Code", model.agentName)
            assertTrue(model.supportsMessageActions)
            assertEquals("tools-native-user", model.messages.last().nativeId)
            assertFalse(requests.any { it.first.endsWith("/overview") || it.first.endsWith("/sessions/context") })
            model.forkMessage(model.messages.first()) {}
            await { model.selectedId == "forked" }
            assertEquals("native-user", requests.first { it.first.endsWith("/fork") }.second.getString("message_id"))
            model.select(row)
            await { model.selectedId == "native-session" && model.messages.size == 2 && !model.historyLoading }
            model.draft = "new message"
            assertTrue(model.send())
            await { model.hasPendingSubmission && !model.submitting }
            assertFalse(model.send())
            assertEquals(1, requests.count { it.first.endsWith("/runs") })
            receiptFound = true
            model.reconcilePending()
            await { !model.hasPendingSubmission && model.runId == "retained-run" }
            assertEquals(1, requests.count { it.first.endsWith("/runs") })
        } finally {
            store.clear(); server.close(); responder.join(1000); shadowOf(Looper.getMainLooper()).idle()
            application.getSharedPreferences("claude-$device", 0).edit().clear().commit()
        }
    }
}
