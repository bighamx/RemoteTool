package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentAttachmentIdentityTest {
    private val hash = "a".repeat(64)
    private val upload = JSONObject().put("id", "upload-id").put("sha256", hash).put("name", "image.jpg")
    private val native = JSONObject().put("id", "native_76787_digest").put("uploadId", "upload-id").put("sha256", hash).put("name", "image.jpg")

    @Test fun canonicalAttachmentReplacesLocalCardWithoutDuplicatingTheImage() {
        val row = HermesMessage("user", "已安装新版 还是消息重复", 76787, timestamp = 20000, attachments = listOf(native))
        val sent = SteeringMessage("key", "session", row.text, setOf(76782), 76782, "已送达", 10000, listOf(upload))
        val (pending, confirmed) = reconcileSteeringMessages(listOf(row), listOf(sent))
        assertTrue(pending.isEmpty())
        assertEquals(row, confirmed.single().second)
        assertEquals(listOf(native), attachConfirmedSubmission(listOf(row), row.serverId, listOf(upload)).single().attachments)
        assertEquals(listOf(row), projectCurrentSubmission(listOf(row), row.copy(serverId = 0, localKey = "key", attachments = listOf(upload)), setOf(76782)))
    }

    @Test fun inlineImagesUseHashesWhileSameNamedDifferentImagesStayDistinct() {
        val inline = JSONObject().put("id", "native_1_inline").put("sha256", hash)
        assertTrue(sameAgentAttachment(upload, inline))
        assertFalse(sameAgentAttachment(upload, JSONObject().put("id", "other").put("name", "image.jpg").put("sha256", "b".repeat(64))))
        assertFalse(sameAgentAttachment(JSONObject(), JSONObject()))
        assertTrue(containsAgentAttachments(listOf(native), listOf(upload, upload)))
        assertFalse(containsAgentAttachments(listOf(native), listOf(upload, JSONObject().put("id", "other").put("sha256", "b".repeat(64)))))
        assertEquals(2, mergeAgentAttachments(listOf(native), listOf(JSONObject().put("id", "different"))).size)
    }

    @Test fun attachmentMismatchOrMissingAcceptanceCannotHideAnUnconfirmedMessage() {
        val sent = SteeringMessage("key", "session", "继续", emptySet(), 0, "已送达", 10000, listOf(upload))
        val other = HermesMessage("user", "继续", 20, timestamp = 11000, attachments = listOf(JSONObject().put("id", "different")))
        assertFalse(steeringAppearsInHistory(sent, listOf(other)))
        assertFalse(steeringAppearsInHistory(sent.copy(delivery = "发送状态待核对"), listOf(other.copy(attachments = listOf(native)))))
    }
}
