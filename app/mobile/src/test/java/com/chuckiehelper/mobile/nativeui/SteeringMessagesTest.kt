package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class SteeringMessagesTest {
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
