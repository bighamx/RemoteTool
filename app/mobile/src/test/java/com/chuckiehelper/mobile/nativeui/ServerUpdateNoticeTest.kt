package com.chuckiehelper.mobile.nativeui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
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

    @Test fun completionAfterClosingTheDialogRemainsHistoricalWhenReopened() {
        val finish = CountDownLatch(1)
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val visible = mutableStateOf(true)
        val server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.method == Method.POST) { finish.await(20, TimeUnit.SECONDS); finished.set(true) }
                val value = JSONObject("""{"supported":true,"installed":true,"mode":"source","canRebuild":true,"supportsSourcePull":true,"check":{"kind":"source","state":"rebuild_required"}}""")
                if (finished.get()) value.put("job", JSONObject().put("id", "closed-job").put("phase", "completed"))
                return newFixedLengthResponse(Response.Status.OK, "application/json", value.toString())
            }
        }
        server.start()
        try {
            val api = NativeApi("http://127.0.0.1:${server.listeningPort}", noRetry = true)
            compose.setContent { MaterialTheme { ServerUpdateNotice(api, "close-fixture", requested = visible.value, onClosed = { visible.value = false }) } }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("编译并重启").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("编译并重启").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithText("正在提交编译并重启请求…").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("关闭").performClick()
            finish.countDown()
            compose.runOnIdle { visible.value = true }
            compose.waitUntil(15_000) { compose.onAllNodesWithText("上次更新：已完成").fetchSemanticsNodes().isNotEmpty() }
        } finally { finish.countDown(); server.stop() }
    }

    @Test fun submitThenPollWaitingBuildingAndDeferredWithoutRepeatingPost() {
        val phase = AtomicReference("")
        val accepted = CountDownLatch(1)
        val posts = java.util.concurrent.atomic.AtomicInteger()
        val submittedPull = AtomicReference<Boolean>()
        val visible = mutableStateOf(true)
        val jobId = AtomicReference("one-job")
        val mode = AtomicReference("source")
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
                val value = JSONObject("""{"supported":true,"installed":true,"mode":"source","supportsSourcePull":true,"canRebuild":true,"currentVersion":"0123456789ab","check":{"kind":"source","state":"rebuild_required","localRevision":"abcdef1234567890","latestRevision":"abcdef1234567890"}}""")
                value.put("mode", mode.get())
                if (mode.get() == "release") value.put("check", JSONObject().put("available", false))
                if (phase.get().isNotEmpty()) value.put("job", JSONObject().put("id",jobId.get()).put("phase",phase.get()).put("message","任务详情："+phase.get()))
                return newFixedLengthResponse(Response.Status.OK, "application/json", value.toString())
            }
        }
        server.start()
        try {
            val api = NativeApi("http://127.0.0.1:${server.listeningPort}", noRetry = true)
            compose.setContent { MaterialTheme { ServerUpdateNotice(api,"fixture",requested = visible.value, onClosed = { visible.value = false }) } }
            fun waitFor(text: String) {
                compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
            }
            waitFor("编译并重启")
            waitFor("源码部署")
            val labels = listOf("运行版本", "本地源码", "远端源码").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
            org.junit.Assert.assertEquals(labels[0].top, labels[1].top)
            org.junit.Assert.assertEquals(labels[1].top, labels[2].top)
            org.junit.Assert.assertTrue(labels[0].left < labels[1].left && labels[1].left < labels[2].left)
            val versionBottom = (compose.onAllNodesWithText("abcdef12").fetchSemanticsNodes() + compose.onNodeWithText("01234567").fetchSemanticsNode()).maxOf { it.boundsInRoot.bottom }
            org.junit.Assert.assertTrue(compose.onNodeWithText("源码已是最新，需编译并重启服务端").fetchSemanticsNode().boundsInRoot.top >= versionBottom)
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
            phase.set("completed")
            waitFor("本次更新：已完成")
            compose.onNodeWithText("关闭").performClick()
            compose.runOnIdle { visible.value = true }
            waitFor("上次更新：已完成")
            jobId.set("previous-deferred-job"); phase.set("deferred")
            waitFor("上次更新：已延后")
            mode.set("release")
            waitFor("发行包部署")
            org.junit.Assert.assertTrue(compose.onAllNodesWithText("本地源码").fetchSemanticsNodes().isEmpty())
            org.junit.Assert.assertTrue(compose.onAllNodesWithText("远端源码").fetchSemanticsNodes().isEmpty())
            org.junit.Assert.assertEquals(1, posts.get())
            org.junit.Assert.assertEquals(false, submittedPull.get())
        } finally { accepted.countDown(); server.stop() }
    }
}
