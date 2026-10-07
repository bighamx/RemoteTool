package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class SteeringMessagesTest {
    @Test fun rejectedSteeringNeverReappearsAsRealHistory() {
        val busy = SteeringMessage("busy", "session", "插话", emptySet(), 0,
            BUSY_STEERING_DELIVERY, 1_000L)
        assertEquals(BUSY_STEERING_DELIVERY,
            steeringFailureDelivery(ApiRequestFailure("会话正在桌面端执行", 503, delivery = "rejected")))
        assertEquals("发送失败", steeringFailureDelivery(ApiRequestFailure("请求无效", 409)))
        assertFalse(steeringAppearsInHistory(busy, listOf(HermesMessage("user", "插话", 1))))
        assertFalse(isAbandonedSteering(busy, 601_000L))
        assertTrue(isAbandonedSteering(busy, 601_001L))
        assertTrue(mergeSteeringMessages(emptyList(), listOf(busy)).isEmpty())
        assertTrue(mergeSteeringMessages(emptyList(), listOf(busy.copy(delivery = "发送失败"))).isEmpty())
        assertEquals("发送状态待核对", steeringFailureDelivery(ApiRequestFailure("未确认", 504)))
    }
    @Test fun hashIdOrderAndAnUnconfirmedOldTimestampDoNotAcknowledgeMessages() {
        val note = SteeringMessage("key", "session", "插话", setOf(100), 100, "发送状态待核对", 1)
        val history = (0 until 500).map { HermesMessage("assistant", "output", 1000L + it, timestamp = 2000L + it) }
        assertEquals(listOf(note), pendingSteeringMessages(history, listOf(note)))
        assertTrue(pendingSteeringMessages(history, listOf(note.copy(delivery = "已送达"))).isEmpty())
        assertEquals(listOf(note.copy(delivery = "已送达")), pendingSteeringMessages(history.take(2), listOf(note.copy(delivery = "已送达"))))
    }
    @Test fun exactMatchWinsOverWindowAgeAndOnlyConsumesOneRepeatedMessage() {
        val note = SteeringMessage("key1", "session", "插话", emptySet(), 100, "已送达", 1)
        val second = note.copy(key = "key2", delivery = "发送状态待核对")
        val history = listOf(HermesMessage("user", "插话", 1, timestamp = 2000)) + (0 until 499).map { HermesMessage("assistant", "output", 1000L + it, timestamp = 2000L + it) }
        val (pending, accepted) = reconcileSteeringMessages(history, listOf(note, second))
        assertEquals(listOf(second), pending)
        assertEquals(note, accepted.single().first)
    }
    @Test fun identicalSteersNeedSeparateHistoryRowsAndPartialTextNeverAcknowledges() {
        val first = SteeringMessage("first", "session", "同意", setOf(10), 10, "已送达")
        val second = first.copy(key = "second")
        val persisted = HermesMessage("user", "同意", 11)
        assertEquals(listOf(second), pendingSteeringMessages(listOf(persisted), listOf(first, second)))
        assertEquals(emptyList<SteeringMessage>(), pendingSteeringMessages(listOf(persisted, persisted.copy(serverId = 12)), listOf(first, second)))
        assertFalse(steeringAppearsInHistory(first, listOf(HermesMessage("user", "不同意", 11))))
        assertFalse(steeringAppearsInHistory(first.copy(delivery = "发送失败"), listOf(persisted)))
        assertTrue(steeringAppearsInHistory(first.copy(text = "first\r\nsecond"), listOf(persisted.copy(text = " first\nsecond "))))
    }
    @Test fun steeringStaysInChatAcrossHistoryRefreshAndMergesWhenPersisted() {
        val old = HermesMessage("user", "start", 10)
        val reply = HermesMessage("assistant", "reply", 11)
        val steering = SteeringMessage("key", "session", "change direction", setOf(10), 10, "已送达")
        val rows = mergeSteeringMessages(listOf(old, reply), listOf(steering))
        assertEquals(listOf("start", "change direction", "reply"), rows.map { it.text })
        assertEquals("已送达", rows[1].delivery)
        val persisted = HermesMessage("user", "change direction", 12)
        assertTrue(steeringAppearsInHistory(steering, listOf(old, persisted)))
        assertEquals(listOf(old, persisted, reply), mergeSteeringMessages(listOf(old, persisted, reply), listOf(steering)))
        assertFalse(steeringAppearsInHistory(steering, listOf(HermesMessage("user", "change direction", 10))))
    }
}
