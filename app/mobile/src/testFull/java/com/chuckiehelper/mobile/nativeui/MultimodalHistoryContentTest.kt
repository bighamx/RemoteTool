package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class MultimodalHistoryContentTest {
    private val question = "当前用的三方，却显示当前走官方账户；模型列表依然不对"
    private fun blocks() = JSONArray().put(obj("type" to "text", "text" to question)).apply {
        repeat(4) { put(obj("type" to "image_url", "image_url" to obj("url" to
            ("data:image/jpeg;base64," + "A".repeat(190_000))))) }
    }
    private fun files() = (1..4).map { obj("id" to "native_78137_$it", "mime" to "image/jpeg", "url" to "/files/$it") }

    @Test fun nativeArrayNeverBecomesTheRenderedOrCachedBody() {
        val message = agentHistoryMessage("hermes", obj("role" to "user", "id" to 78137,
            "content" to blocks(), "attachments" to JSONArray(files())))!!
        assertEquals(question, message.text)
        assertEquals(4, message.attachments.size)
        assertFalse(encodeHistorySnapshot(listOf(message)).toString().contains("data:image"))
        assertEquals(question, presentHermesMessage(message.text, message.attachments, emptyList(), "user").text)
    }

    @Test fun oldSerializedSnapshotsRecoverTextAndKeepAllFourAttachments() {
        val snapshot = JSONArray().put(obj("role" to "user", "id" to 78137, "text" to blocks().toString(),
            "timestamp" to 1793195580000L, "editable" to true, "attachments" to JSONArray(files())))
        assertTrue(snapshot.toString().length > 760_000)
        val message = decodeHistorySnapshot(snapshot).single()
        assertEquals(question, message.text)
        assertEquals(78137L, message.serverId)
        assertEquals(4, message.attachments.size)
        assertEquals(1793195580000L, message.timestamp)
        assertTrue(message.editable)
        assertFalse(encodeHistorySnapshot(listOf(message)).toString().contains("base64"))
    }

    @Test fun alreadyLoadedSerializedMessageIsSafeAtTheDisplayBoundary() {
        val shown = presentHermesMessage(blocks().toString(), files(), emptyList(), "user")
        assertEquals(question, shown.text)
        assertEquals(4, shown.files.size)
    }

    @Test fun plainTextCodeAndUnrelatedJsonArePreserved() {
        val examples = listOf("ordinary text", "null", "[]", "[{\"text\":\"data:image/jpeg;base64,AAAA\"}]",
            "```json\n" + blocks().toString() + "\n```", "[{\"type\":\"text\",\"text\":\"example\"}]")
        examples.forEach { assertEquals(it, agentMessageContentText(it)) }
        assertEquals("", agentMessageContentText(JSONArray().put(obj("type" to "image_url",
            "image_url" to obj("url" to "data:image/jpeg;base64,AAAA")))))
    }
}
