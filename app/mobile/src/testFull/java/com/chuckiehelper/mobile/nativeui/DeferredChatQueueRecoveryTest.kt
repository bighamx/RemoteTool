package com.chuckiehelper.mobile.nativeui

import android.content.SharedPreferences
import com.chuckiehelper.mobile.phonecontrol.PhoneWebApi
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DeferredChatQueueRecoveryTest {
    private fun preferences(rows: List<QueuedChatMessage>): SharedPreferences {
        val values = mutableMapOf<String, Any>("message_queue" to queuedMessagesJson(rows))
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            when (method.name) {
                "putString", "putBoolean" -> { values[args[0] as String] = args[1]; editor }
                "apply" -> null
                "commit" -> true
                else -> editor
            }
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean" -> values[args[0] as String] ?: args[1]
                "edit" -> editor
                else -> null
            }
        } as SharedPreferences
    }

    @Test fun absentReceiptsStayUncertainAndBlockLaterInstructionsUntilExplicitlyRemoved() = runBlocking {
        var receipt = obj("found" to false)
        val api = object : PhoneWebApi {
            override val base = "http://test"
            override fun cookie() = ""
            override fun request(path: String, body: JSONObject?): Request = error("not used")
            override suspend fun json(request: Request): JSONObject = error("not used")
            override suspend fun json(path: String, body: JSONObject?): JSONObject = when {
                path.contains("lookup") -> receipt
                path.endsWith("active-run") -> obj("run_id" to null)
                path.endsWith("activity") -> obj("available" to true, "running" to false)
                else -> error("Unexpected request $path")
            }
        }
        val rows = listOf(
            QueuedChatMessage("first", "s", "uncertain", emptyList(), mode="local", status="发送结果待核对", accountId="hermes"),
            QueuedChatMessage("second", "s", "later", emptyList(), mode="local", status="等待任务结束", accountId="hermes"))
        val sent = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val queue = DeferredChatQueue(preferences(rows), scope, "hermes", { api }, { "s" }, { true }, { "hermes" }, { row, _ -> sent += row.key })
            repeat(5) { queue.tick("s") }
            assertEquals("发送结果待核对", queue.entries.first().status)
            assertTrue(sent.isEmpty())
            receipt = obj("found" to true, "session_id" to "s", "status" to "failed", "delivery" to "rejected")
            queue.tick("s")
            assertEquals("发送失败，已暂停", queue.entries.first().status)
            assertTrue(sent.isEmpty())
            queue.remove(queue.entries.first())
            queue.tick("s")
            assertEquals(listOf("second"), sent)
        } finally { scope.cancel() }
    }
}
