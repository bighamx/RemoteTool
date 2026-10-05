package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SteeringAttachmentsTest {
    @Test fun imageAttachmentIsShownImmediatelyOnTheSteeringBubble() {
        val file = JSONObject().put("id", "image-id").put("mime", "image/png").put("name", "screen.png")
        val steer = SteeringMessage("key", "session", "看图", setOf(1), 1, attachments = listOf(file))
        val merged = mergeSteeringMessages(listOf(HermesMessage("user", "开始任务", 1)), listOf(steer))
        assertEquals("看图", merged.last().text)
        assertEquals("image-id", merged.last().attachments.single().getString("id"))
        assertEquals("正在发送", merged.last().delivery)
    }
    @Test fun repeatedTextBindsEachAttachmentToItsOwnCanonicalMessage() {
        val first = SteeringMessage("key1", "session", "看图", setOf(1), 1, attachments = listOf(JSONObject().put("id", "image-1")))
        val second = first.copy(key = "key2", attachments = listOf(JSONObject().put("id", "image-2")))
        val history = listOf(HermesMessage("user", "看图", 2), HermesMessage("user", "看图", 3))
        val (pending, accepted) = reconcileSteeringMessages(history, listOf(first, second))
        assertTrue(pending.isEmpty())
        assertEquals(listOf(2L, 3L), accepted.map { it.second.serverId })
        assertEquals(listOf("image-1", "image-2"), accepted.map { it.first.attachments.single().getString("id") })
    }
    @Test fun failedSteeringKeepsItsAttachmentForRetryAndHistoryCannotAcknowledgeIt() {
        val steer = SteeringMessage("key", "session", "看图", setOf(1), 1, delivery = "发送失败", attachments = listOf(JSONObject().put("id", "image")))
        val (pending, accepted) = reconcileSteeringMessages(listOf(HermesMessage("user", "看图", 2)), listOf(steer))
        assertTrue(accepted.isEmpty())
        assertEquals("image", pending.single().attachments.single().getString("id"))
    }
}
