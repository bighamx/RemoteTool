package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.os.Looper
import androidx.lifecycle.ViewModelStore
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import org.json.JSONArray
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
class HistoryColdStartTest {
    @Test fun restoredOldReceiptCannotHideNewerDiskHistoryWhileNetworkIsStillLoading() {
        val application = RuntimeEnvironment.getApplication()
        val device = "cold-start-${UUID.randomUUID()}"
        val session = "completed-session"
        val prefs = application.getSharedPreferences("codex-$device", 0)
        val old = FinalSyncState(session, "old-run", null, "old-turn", "旧的待同步回复", 1792800001000L, 1792800000900L)
        prefs.edit().putString("finalSyncStates", JSONArray().put(old.json()).toString()).commit()
        val latest = HermesMessage("assistant", "已完成，最新的完整回复", 2L, timestamp = 1792800005000L, nativeTurnId = "latest-turn")
        val cache = AgentHistoryCache(application.noBackupFilesDir.resolve("conversation-history"), "codex-$device")
        cache.write(session, listOf(HermesMessage("user", "最新请求", 1L, timestamp = 1792800004000L), latest))
        val store = ViewModelStore()
        // Hold the TCP connection without serving a response. The test must succeed
        // from disk, independently of any successful overview/history network read.
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { server ->
            val model = HermesModel(application, device, "codex")
            store.put("history", model)
            HermesModel::class.java.getDeclaredField("api").apply { isAccessible = true }
                .set(model, NativeApi("http://127.0.0.1:${server.localPort}"))
            try {
                model.select(obj("id" to session, "title" to "已结束的会话"))
                assertTrue("Do not flash an old receipt before reading the cache", model.messages.isEmpty())
                val deadline = System.nanoTime() + 5_000_000_000L
                while (model.messages.none { it.serverId == latest.serverId } && System.nanoTime() < deadline) {
                    shadowOf(Looper.getMainLooper()).idle()
                    Thread.sleep(10)
                }
                assertEquals(latest, model.messages.lastOrNull())
                assertFalse("A replayed history window is no longer initial loading", model.historyLoading)
                assertTrue("The receipt remains available for reconciliation", model.finalSyncPending)
            } finally {
                store.clear()
                shadowOf(Looper.getMainLooper()).idle()
                cache.remove(session)
                prefs.edit().clear().commit()
            }
        }
    }
}
