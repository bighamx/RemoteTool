package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class FilesLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compactRowsKeepToolsAtBottomAndSettingsAtTop() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val drive = JSONObject().put("data", JSONArray().put(JSONObject().put("name", "files").put("path", "D:/files/").put("isDirectory", true))).toString()
        val directory = JSONObject().put("data", JSONArray((0..99).map { index -> JSONObject()
            .put("name", "file-%03d.txt".format(index)).put("path", "D:/files/$index.txt")
            .put("isDirectory", false).put("size", index).put("modified", "2026-10-18T10:00:00") })).toString()
        val responder = thread(isDaemon = true) {
            try { listOf(drive, directory).forEach { response -> server.accept().use { socket ->
                socket.soTimeout = 5000
                val reader = socket.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) { }
                socket.getOutputStream().apply {
                    write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${response.toByteArray().size}\r\nConnection: close\r\n\r\n" + response).toByteArray()); flush()
                }
            } } } catch (_: java.io.IOException) { }
        }
        try {
            val api = NativeApi("http://127.0.0.1:${server.localPort}")
            var dispatcher: OnBackPressedDispatcher? = null
            var closed = false
            compose.setContent { MaterialTheme { Surface(Modifier.width(360.dp).height(640.dp)) {
                val owner = LocalOnBackPressedDispatcherOwner.current
                SideEffect { dispatcher = owner!!.onBackPressedDispatcher }
                FilesScreen(api, onError = { throw AssertionError(it) }, onClose = { closed = true }, onCompose = {})
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("files").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("files").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
            compose.onNodeWithText("files").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("file-000.txt").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("file-000.txt").performTouchInput { longClick() }
            compose.onNodeWithText("file-001.txt").performClick()
            compose.onNodeWithText("文件夹 0 · 文件 100 · 已选 2").assertIsDisplayed()
            compose.runOnUiThread { dispatcher!!.onBackPressed() }
            compose.onNodeWithText("D:/files/").assertIsDisplayed()
            compose.onNodeWithText("文件夹 0 · 文件 100").assertIsDisplayed()
            compose.onNodeWithText("file-000.txt").performTouchInput { longClick() }
            compose.onNodeWithContentDescription("返回").performClick()
            assertFalse("Toolbar back must cancel selection before leaving", closed)
            compose.onNodeWithText("D:/files/").assertIsDisplayed()
            compose.onNodeWithText("文件夹 0 · 文件 100").assertIsDisplayed()
            val row = compose.onNodeWithText("file-000.txt").getUnclippedBoundsInRoot()
            assertTrue("File row is compact: $row", row.bottom - row.top <= 54.dp)
            compose.onNodeWithText("D:/files/").assertIsDisplayed()
            compose.onNodeWithText("文件夹 0 · 文件 100").assertIsDisplayed()
            assertTrue(compose.onNodeWithContentDescription("视图设置").getUnclippedBoundsInRoot().top < 72.dp)
            assertTrue(compose.onNodeWithText("上传").getUnclippedBoundsInRoot().top > 560.dp)
            compose.onNodeWithContentDescription("视图设置").performClick()
            // 主/次两行都有「大小」chip；点主排序行的第一个。
            compose.onAllNodesWithText("大小")[0].performClick()
            // 主/次两行各有「降序」；点主排序行的第一个。
            compose.onAllNodesWithText("降序")[0].performClick()
            compose.onNodeWithText("完成").performClick()
            compose.onNodeWithText("file-099.txt").assertIsDisplayed()
        } finally { server.close(); responder.join(1000) }
    }
}
