package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageSubmissionPresentationTest {
    private val image = JSONObject().put("id", "image-1").put("mime", "image/jpeg").put("name", "photo.jpg")

    @Test fun acceptedImageWithLegacyTextOnlyHistoryUsesOneBubbleWithoutChangingReceipt() {
        val sent = HermesMessage("user", "ubuntu 配置无效", attachments = listOf(image), localKey = "live-send", timestamp = 100_000L)
        val native = HermesMessage("user", sent.text, 201, timestamp = 101_000L)
        val history = listOf(HermesMessage("assistant", "previous", 200), native)
        val visible = projectCurrentSubmission(history, sent, setOf(200L))
        assertEquals(listOf(200L, 201L), visible.map { it.serverId })
        assertEquals("image-1", visible.last().attachments.single().getString("id"))
        assertNull(visible.last().requestKey)
        assertNull(visible.last().localKey)
        assertTrue(native.attachments.isEmpty())
        assertEquals(visible, projectCurrentSubmission(history, sent, setOf(200L), allowAppend = false))
        assertEquals(emptyList<HermesMessage>(), projectCurrentSubmission(emptyList(), sent, setOf(200L), allowAppend = false))
        assertEquals(listOf(200L, 0L, 201L), projectCurrentSubmission(history,
            sent.copy(delivery = "发送状态待核对"), setOf(200L)).map { it.serverId })
    }

    @Test fun legacyImagePreviewCannotAttachToOldDelayedOrAmbiguousSameText() {
        val sent = HermesMessage("user", "看图", attachments = listOf(image), localKey = "live-send", timestamp = 100_000L)
        val native = HermesMessage("user", "看图", 201, timestamp = 101_000L)
        assertEquals(3, projectCurrentSubmission(listOf(native, native.copy(serverId = 202)), sent, emptySet()).size)
        assertEquals(2, projectCurrentSubmission(listOf(native.copy(timestamp = 120_000L)), sent, emptySet()).size)
        assertEquals(2, projectCurrentSubmission(listOf(native), sent, setOf(201L)).size)
        assertEquals(2, projectCurrentSubmission(listOf(native.copy(requestKey = "other")), sent, emptySet()).size)
    }

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

    @Test fun undatedNativeRowsStayAheadOfUncertainLocalImages() {
        val old = HermesMessage("assistant", "之前", 90)
        val reply = HermesMessage("assistant", "正在执行", 2)
        val first = HermesMessage("user", "第一张", attachments = listOf(image), localKey = "first")
        val second = first.copy(text = "第二张", localKey = "second")
        val one = projectCurrentSubmission(listOf(old, reply), first, setOf(90L))
        val two = projectCurrentSubmission(one, second, setOf(90L))
        assertEquals(listOf(old, reply, first, second), two)
        assertNull(two[2].delivery)
        assertNull(two[3].delivery)
    }

    @Test fun acceptedLocalRowIsNotDrawnBesideItsSingleNewNativeCopy() {
        val pending = HermesMessage("user", "同步状态", localKey = "request", timestamp = 10_000L)
        val native = HermesMessage("user", "同步状态", 99, timestamp = 18_000L)
        assertEquals(listOf(native), projectCurrentSubmission(listOf(native), pending, emptySet()))
        assertEquals(listOf(pending.copy(delivery = "发送状态待核对"), native), projectCurrentSubmission(listOf(native),
            pending.copy(delivery = "发送状态待核对"), emptySet()))
    }

    @Test fun oldSameTextOrDifferentImageDoesNotHideARealPendingRow() {
        val pending = HermesMessage("user", "看图", attachments = listOf(image), localKey = "request", timestamp = 100_000L)
        val old = HermesMessage("user", "看图", 20, attachments = listOf(image), timestamp = 10_000L)
        val another = HermesMessage("user", "看图", 21,
            attachments = listOf(JSONObject().put("id", "other-image")), timestamp = 108_000L)
        assertEquals(listOf(old, pending, another), projectCurrentSubmission(listOf(old, another), pending, emptySet()))
    }

    @Test fun twoAcceptedRetriesUseTwoDifferentNativeRowsEvenWhenPersistenceIsDelayed() {
        val first = HermesMessage("user", "重复与否是不固定的", localKey = "first", timestamp = 100_000L)
        val second = first.copy(localKey = "second", timestamp = 160_000L)
        val firstNative = HermesMessage("user", first.text, 201, timestamp = 170_000L)
        val delayedNative = HermesMessage("user", first.text, 202, timestamp = 520_000L)
        val used = mutableSetOf<Long>()
        val history = listOf(firstNative, delayedNative)
        val once = projectCurrentSubmission(history, first, emptySet(), used)
        val twice = projectCurrentSubmission(once, second, emptySet(), used)
        assertEquals(history, twice)
        assertEquals(setOf(201L, 202L), used)

        val oneNativeOnly = mutableSetOf<Long>()
        val one = projectCurrentSubmission(listOf(firstNative), first, emptySet(), oneNativeOnly)
        val two = projectCurrentSubmission(one, second, emptySet(), oneNativeOnly)
        assertEquals(2, two.size)
        assertEquals(1, two.count { it.serverId == 0L })
    }
}
