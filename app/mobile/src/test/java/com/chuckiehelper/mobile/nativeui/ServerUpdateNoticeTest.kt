package com.chuckiehelper.mobile.nativeui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Exercise the real dialog, NativeApi POST and polling together, without touching a real server. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ServerUpdateNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun submitThenPollWaitingBuildingAndDeferredWithoutRepeatingPost() {
        val phase = AtomicReference("")
        val accepted = CountDownLatch(1)
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val submittedPull = AtomicReference<Boolean>()
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.method == Method.POST) {
                    val body = mutableMapOf<String,String>()
                    session.parseBody(body)
                    submittedPull.set(JSONObject(body.getValue("postData")).optBoolean("pullSource"))
                    posts.incrementAndGet()
                    accepted.await(20, TimeUnit.SECONDS)
                    phase.set("waiting")
                }
                val value = JSONObject("""{"supported":true,"installed":true,"mode":"source","supportsSourcePull":true,"canRebuild":true,"currentVersion":"test","check":{"kind":"source","state":"rebuild_required","message":"源码已更新"}}""")
                if (phase.get().isNotEmpty()) value.put("job", JSONObject().put("id","one-job").put("phase",phase.get()).put("message","任务详情："+phase.get()))
                return newFixedLengthResponse(Response.Status.OK, "application/json", value.toString())
            }
        }
        server.start()
        try {
            val api = NativeApi("http://127.0.0.1:${server.listeningPort}", noRetry = true)
            compose.setContent { MaterialTheme { ServerUpdateNotice(api,"fixture",requested = true) } }
            fun waitFor(text: String) {
                compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            }
            waitFor("编译并重启")
            org.junit.Assert.assertTrue(compose.onAllNodesWithText("编译当前源码").fetchSemanticsNodes().isEmpty())
            org.junit.Assert.assertTrue(compose.onAllNodesWithText("拉取并更新").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithText("编译并重启").performClick()
            waitFor("正在提交编译并重启请求…")
            compose.onNodeWithText("编译并重启").assertIsNotEnabled()
            accepted.countDown()
            waitFor("等待任务空闲")
            waitFor("任务详情：waiting")
            phase.set("building")
            waitFor("正在编译并重启")
            phase.set("deferred")
            waitFor("上次更新已延后")
            org.junit.Assert.assertEquals(1, posts.get())
            org.junit.Assert.assertEquals(false, submittedPull.get())
        } finally { accepted.countDown(); server.stop() }
    }
}
