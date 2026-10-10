package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageSubmissionPresentationTest {
    private val image = JSONObject().put("id", "image-1").put("mime", "image/jpeg").put("name", "photo.jpg")

    @Test fun replyBeforeUserHistoryDoesNotHideSendingImage() {
        val old = HermesMessage("assistant", "上一轮", 90, timestamp = 100L)
        val reply = HermesMessage("assistant", "正在看图片", 2, timestamp = 300L)
        val sending = HermesMessage("user", "看图", attachments = listOf(image), localKey = "current",
            delivery = "正在发送", timestamp = 200L)
        val displayed = projectCurrentSubmission(listOf(old, reply), sending, setOf(90L))
        assertEquals(listOf(old, sending, reply), displayed)
        assertEquals("image-1", displayed[1].attachments.single().getString("id"))
        assertEquals(displayed, projectCurrentSubmission(displayed, sending, setOf(90L)))
    }

    @Test fun confirmedNativeRowImmediatelyKeepsImageAndDropsSendingLabel() {
        val history = listOf(HermesMessage("assistant", "之前", 90),
            HermesMessage("user", "看图", 2, delivery = "正在发送"), HermesMessage("assistant", "回复", 70))
        val confirmed = attachConfirmedSubmission(history, 2, listOf(image))
        assertEquals(listOf(90L, 2L, 70L), confirmed.map { it.serverId })
        assertEquals("image-1", confirmed[1].attachments.single().getString("id"))
        assertNull(confirmed[1].delivery)
        assertEquals(1, attachConfirmedSubmission(confirmed, 2, listOf(image))[1].attachments.size)
        assertEquals(history[0], confirmed[0])
        assertEquals(history[2], confirmed[2])
    }

    @Test fun imageOnlySendSurvivesRepeatedEmptySnapshotsUntilNativeAcknowledgement() {
        val sending = HermesMessage("user", "", attachments = listOf(image), localKey = "current", delivery = "正在发送")
        repeat(3) {
            val visible = projectCurrentSubmission(emptyList(), sending, emptySet())
            assertEquals(listOf(sending), visible)
            assertTrue(visibleAgentMessage(visible[0].role, visible[0].text, visible[0].attachments.size))
        }
    }

    @Test fun acceptedSteeredImagesStayVisibleInSendOrderWhileNativeHistoryLags() {
        val old = HermesMessage("assistant", "之前", 90)
        val reply = HermesMessage("assistant", "正在执行", 2)
        val first = HermesMessage("user", "第一张", attachments = listOf(image), localKey = "first")
        val second = first.copy(text = "第二张", localKey = "second")
        val one = projectCurrentSubmission(listOf(old, reply), first, setOf(90L))
        val two = projectCurrentSubmission(one, second, setOf(90L))
        assertEquals(listOf(old, first, second, reply), two)
        assertNull(two[1].delivery)
        assertNull(two[2].delivery)
    }
}
