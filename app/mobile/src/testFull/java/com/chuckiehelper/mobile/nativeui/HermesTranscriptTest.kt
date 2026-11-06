package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.lifecycle.ViewModelStore
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HermesTranscriptTest {
    private val base = 1793960846000L
    @Suppress("UNCHECKED_CAST")
    private fun state(model: HermesModel, name: String, value: Any?) {
        val field = HermesModel::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
        (field.get(model) as MutableState<Any?>).value = value
    }
    private fun fixture(test: (HermesModel) -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        val device = "transcript-${UUID.randomUUID()}"
        val prefs = app.getSharedPreferences("hermes-$device", 0)
        prefs.edit().putString("session", "s").commit()
        val model = HermesModel(app, device)
        val store = ViewModelStore().apply { put("model", model) }
        HermesModel::class.java.getDeclaredField("api").apply { isAccessible = true }.set(model, NativeApi("http://127.0.0.1:1"))
        state(model, "runId", "r"); state(model, "runSession", "s")
        try { test(model) } finally { store.clear(); prefs.edit().clear().commit() }
    }
    private fun emit(model: HermesModel, seq: Long, kind: String, text: String, streamed: Boolean = false, uid: String? = null) {
        val event = obj("event" to kind, "timestamp" to base / 1000.0,
            (if (kind == "message.delta") "delta" else "text") to text, "already_streamed" to streamed, "message_uid" to uid)
        assertFalse(model.receiveReasoningEvent("r", event))
        assertTrue(model.receiveAssistantTextEvent("r", event, seq))
    }
    private fun history(model: HermesModel, rows: JSONArray) = runBlocking {
        val method = HermesModel::class.java.getDeclaredMethod("loadHistoryNow", String::class.java,
            Boolean::class.javaPrimitiveType, JSONObject::class.java, JSONObject::class.java, Continuation::class.java).apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
            method.invoke(model, "s", false, obj("data" to rows, "has_more" to false), obj("data" to JSONArray()), continuation)
        }
    }
    @Test fun reasoningSseParserAndModelPreserveFragmentsAndKeepAssistantHistorySeparate() = fixture { model ->
        val parser = HermesSseParser()
        fun event(kind: String, delta: Any? = null, run: String = "r"): Boolean {
            parser.line("event: $kind")
            parser.line("data: " + obj("event" to kind, "run_id" to run, "timestamp" to base / 1000.0, "delta" to delta))
            return model.receiveReasoningEvent("r", JSONObject(parser.line("")!!))
        }
        assertTrue(event("reasoning.delta", "<thi"))
        assertTrue(event("reasoning.delta", "nk>先检查🙂\n"))
        assertTrue(event("reasoning.delta", "再确认</think>"))
        assertFalse(event("future.event", "忽略"))
        assertEquals("", model.reasoningText)
        model.flushReasoning()
        assertEquals("<think>先检查🙂\n再确认</think>", model.reasoningText)
        assertTrue(model.messages.isEmpty()); assertNull(model.chatTextStream)
        assertEquals(1, model.messagesWithReasoning.size)
        assertTrue(model.messagesWithReasoning.single().narration)
        emit(model, 1, "message.delta", "正常回复")
        assertEquals("正常回复", model.chatTextStream?.text)
        assertTrue(event("reasoning.delta", "最终片段"))
        assertFalse(event("run.completed"))
        assertTrue(model.reasoningEnded)
        assertEquals("<think>先检查🙂\n再确认</think>最终片段", model.reasoningText)
        assertEquals(2, model.messagesWithReasoning.count { it.messageType == "reasoning" })
        assertTrue(event("reasoning.delta", "迟到"))
        model.flushReasoning()
        assertFalse(model.reasoningText.endsWith("迟到"))
        model.observeReasoningRun("r", "s") // A reconnect must preserve the completed buffer.
        assertTrue(model.reasoningText.isNotEmpty())
        state(model, "runId", "next")
        model.observeReasoningRun("next", "s")
        assertEquals("", model.reasoningText); assertFalse(model.reasoningEnded)
        assertTrue(model.receiveReasoningEvent("next", obj("event" to "reasoning.delta", "run_id" to "r", "delta" to "旧轮")))
        for (delta in listOf(null, 7, obj(), ""))
            assertTrue(model.receiveReasoningEvent("next", obj("event" to "reasoning.delta", "run_id" to "next", "delta" to delta)))
        model.flushReasoning(); assertEquals("", model.reasoningText)
        assertTrue(model.receiveReasoningEvent("next", obj("event" to "reasoning.delta", "run_id" to "next", "delta" to "新轮")))
        model.flushReasoning(); assertEquals("新轮", model.reasoningText)
        state(model, "selectedId", "other")
        assertEquals("reasoning cannot leak into another conversation", "", model.reasoningText)
    }
    @Test fun reasoningTokenBurstsPublishOnTheRenderTimer() = fixture { model ->
        repeat(200) { index ->
            assertTrue(model.receiveReasoningEvent("r", obj("event" to "reasoning.delta", "run_id" to "r", "delta" to "$index,")))
        }
        assertEquals("", model.reasoningText)
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(150))
        assertEquals((0 until 200).joinToString(separator = "", transform = { "$it," }), model.reasoningText)
        assertFalse(model.reasoningEnded)
    }
    @Test fun reasoningUsesNarrationRowsWithoutMovingNativeMessagesOrMergingWithTheAnswer() = fixture { model ->
        val user = obj("id" to 1, "role" to "user", "content" to "请求", "timestamp" to (base + 5) / 1000.0)
        history(model, JSONArray().put(user))
        model.receiveReasoningEvent("r", obj("event" to "reasoning.delta", "run_id" to "r", "timestamp" to base / 1000.0, "delta" to "第一段思考"))
        assertFalse(model.receiveReasoningEvent("r", obj("event" to "tool.started")))
        model.receiveReasoningEvent("r", obj("event" to "reasoning.delta", "run_id" to "r", "timestamp" to (base + 2000) / 1000.0, "delta" to "第二段思考"))
        assertFalse(model.receiveReasoningEvent("r", obj("event" to "message.delta")))
        val answer = obj("id" to 2, "role" to "assistant", "content" to "最终回复", "timestamp" to (base + 3000) / 1000.0)
        val steer = obj("id" to 3, "role" to "user", "content" to "插话", "timestamp" to (base + 4000) / 1000.0)
        history(model, JSONArray().put(user).put(answer).put(steer))
        model.receiveReasoningEvent("r", obj("event" to "reasoning.delta", "run_id" to "r", "timestamp" to (base + 5000) / 1000.0, "delta" to "插话后的思考"))
        model.flushReasoning()
        assertEquals(listOf("请求", "第一段思考", "第二段思考", "最终回复", "插话", "插话后的思考"), model.messagesWithReasoning.map { it.text })
        assertEquals(listOf(1L, 2L, 3L), model.messages.map { it.serverId })
        assertTrue(model.messagesWithReasoning.filter { it.messageType == "reasoning" }.all { it.narration && it.serverId == 0L })
    }
    @Test fun actualModelStreamInterimHistoryAndReplayKeepNativeOrderWithoutDuplicateOrTagBubble() = fixture { model ->
        val user = obj("id" to 84840, "role" to "user", "content" to "修复会话残留", "timestamp" to (base + 5) / 1000.0)
        history(model, JSONArray().put(user))
        emit(model, 1, "message.delta", "</thi")
        assertNull(model.chatTextStream)
        emit(model, 2, "message.delta", "nk>\n收到，两件事都做。")
        assertEquals("收到，两件事都做。", model.chatTextStream?.text)
        emit(model, 3, "message.interim", "收到，两件事都做。", true)
        assertEquals(1, model.messages.count { it.role == "assistant" })
        val reply = obj("id" to 84841, "role" to "assistant", "content" to "收到，两件事都做。", "timestamp" to base / 1000.0,
            "tool_calls" to JSONArray().put(obj("id" to "call", "function" to obj("name" to "terminal"))))
        val native = JSONArray().put(user).put(reply)
        history(model, native)
        assertEquals(listOf(84840L, 84841L), model.messages.map { it.serverId })
        emit(model, 4, "message.interim", "收到，两件事都做。", true)
        history(model, native)
        assertEquals(listOf(84840L, 84841L), model.messages.map { it.serverId })
        state(model, "pendingTextTimestamp", base + 30000)
        emit(model, 5, "message.delta", "</think>\n尚未落盘的内容")
        history(model, native)
        assertEquals("尚未落盘的内容", model.chatTextStream?.text)
        assertEquals(3, chatMessageItems(model.messages, model.chatTextStream).size)
    }
    @Test fun delayedEventsStillAcknowledgeNativeAppendOrderUsingAnExplicitUserBinding() {
        val history = listOf(HermesMessage("user", "请求", 84840, timestamp = base + 5),
            HermesMessage("assistant", "收到", 84841, timestamp = base))
        val note = AssistantNarration("n", "s", "收到", 84840, "请求", base + 60000, userTimestamp = base + 5, run = "r", streamed = true)
        assertEquals(history, withActiveNarrations(history, listOf(note), "r", legacyHermes = true))
        val second = note.copy(key = "second", timestamp = base + 90000)
        assertEquals(2, withActiveNarrations(history, listOf(note, second), "r", legacyHermes = true).count { it.role == "assistant" })
    }
    @Test fun reasoningEnvelopeCleanupPreservesLiteralUserAndCodeText() {
        assertEquals("答复", hermesAssistantText("<think>内部内容</think>答复"))
        assertEquals("", hermesAssistantText("<think>尚未结束"))
        for (raw in listOf("引用 </think> 标签", "```xml\n</think>\n```")) assertEquals(raw, hermesAssistantText(raw))
        assertEquals("</think>用户原文", agentHistoryMessage("hermes", obj("role" to "user", "content" to "</think>用户原文"))?.text)
        assertEquals("</think>原文", agentHistoryMessage("codex", obj("role" to "assistant", "content" to "</think>原文"))?.text)
    }
    @Test fun nativeUidControlsActualModelAndSurvivesCacheWithoutTextOrTimestampGuessing() = fixture { model ->
        val first = "11111111111111111111111111111111"
        val second = "22222222222222222222222222222222"
        val native = obj("id" to 84841, "role" to "assistant", "content" to "同样的答复", "timestamp" to (base - 600000) / 1000.0, "message_uid" to first)
        history(model, JSONArray().put(native))
        emit(model, 1, "message.delta", "同样的答复", uid = second)
        assertEquals(2, chatMessageItems(model.messages, model.chatTextStream).size)
        emit(model, 2, "message.interim", "同样的答复", true, second)
        history(model, JSONArray().put(native))
        assertEquals(2, model.messages.size)
        val confirmed = JSONObject(native.toString()).put("id", 84842).put("message_uid", second)
        history(model, JSONArray().put(native).put(confirmed))
        assertEquals(listOf(84841L, 84842L), model.messages.map { it.serverId })
        assertEquals(listOf(first, second), decodeHistorySnapshot(encodeHistorySnapshot(model.messages)).map { it.messageUid })
        val keys = chatMessageKeys(model.messages)
        assertEquals(keys, chatMessageKeys(model.messages.map { it.copy(serverId = it.serverId + 1000) }))
    }
    @Test fun uidArrivingOnlyAtInterimUpgradesThePendingStreamAndPartialNativeRow() = fixture { model ->
        val uid = "33333333333333333333333333333333"
        emit(model, 1, "message.delta", "</think>完整的答复")
        emit(model, 2, "message.interim", "完整的答复", true, uid)
        assertEquals(1, model.messages.size)
        assertEquals(uid, model.messages.single().messageUid)
        history(model, JSONArray().put(obj("id" to 10, "role" to "assistant", "content" to "完整的", "message_uid" to uid, "timestamp" to base / 1000.0)))
        assertEquals(1, model.messages.size)
        assertEquals("完整的答复", model.messages.single().text)
        history(model, JSONArray().put(obj("id" to 10, "role" to "assistant", "content" to "完整的答复", "message_uid" to uid, "timestamp" to base / 1000.0)))
        assertEquals(1, model.messages.size)
        assertEquals(10L, model.messages.single().serverId)
    }
    @Test fun finalReceiptRequiresTheExactUidAndKeepsOnePartialRow() {
        val sync = FinalSyncState("s", "r", null, null, "完成", base, base, messageUid = "uid-current")
        val other = HermesMessage("assistant", "完成", 1, timestamp = base, messageUid = "uid-other")
        assertFalse(finalSyncAcknowledged(sync, listOf(other)))
        assertEquals(2, projectFinalSyncRows(listOf(other), listOf(sync), "s").size)
        val native = other.copy(messageUid = "uid-current")
        assertTrue(finalSyncAcknowledged(sync, listOf(native)))
        assertEquals(listOf(native), projectFinalSyncRows(listOf(native), listOf(sync), "s"))
        assertEquals(sync, restoreFinalSyncStates(JSONArray().put(sync.json()).toString()).single())
    }
}
