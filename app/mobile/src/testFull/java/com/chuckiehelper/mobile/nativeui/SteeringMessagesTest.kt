package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class SteeringMessagesTest {
    @Test fun uncertainSteeringNeverExpiresOrUnlocksResendAndIsScopedToItsAccount() {
        val uncertain = SteeringMessage("key", "session", "once", emptySet(), 0,
            "发送状态待核对", 1000L, accountId = "account-a")
        assertFalse(isAbandonedSteering(uncertain, 1000L + 7 * 86400000L))
        assertTrue(hasUnconfirmedSteering(listOf(uncertain), "session", "account-a"))
        assertFalse(hasUnconfirmedSteering(listOf(uncertain), "session", "account-b"))
        assertFalse(hasUnconfirmedSteering(listOf(uncertain.copy(delivery = "已送达")), "session", "account-a"))
        assertFalse(hasUnconfirmedSteering(listOf(uncertain.copy(delivery = "发送失败")), "session", "account-a"))
    }
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
        val first = SteeringMessage("first", "session", "同意", setOf(10), 10, "已送达", 1000)
        val second = first.copy(key = "second", timestamp = 200000)
        val persisted = HermesMessage("user", "同意", 11, timestamp = 2000)
        assertEquals(listOf(second), pendingSteeringMessages(listOf(persisted), listOf(first, second)))
        assertEquals(emptyList<SteeringMessage>(), pendingSteeringMessages(listOf(persisted, persisted.copy(serverId = 12, timestamp = 201000)), listOf(first, second)))
        assertFalse(steeringAppearsInHistory(first, listOf(HermesMessage("user", "不同意", 11))))
        assertFalse(steeringAppearsInHistory(first.copy(delivery = "发送失败"), listOf(persisted)))
        assertTrue(steeringAppearsInHistory(first.copy(text = "first\r\nsecond"), listOf(persisted.copy(text = " first\nsecond "))))
    }
    @Test fun steeringStaysInChatAcrossHistoryRefreshAndMergesWhenPersisted() {
        val old = HermesMessage("user", "start", 10)
        val reply = HermesMessage("assistant", "reply", 11)
        val steering = SteeringMessage("key", "session", "change direction", setOf(10), 10, "已送达", 1000)
        val rows = mergeSteeringMessages(listOf(old, reply), listOf(steering))
        assertEquals(listOf("start", "change direction", "reply"), rows.map { it.text })
        assertEquals("已送达", rows[1].delivery)
        val persisted = HermesMessage("user", "change direction", 12, timestamp = 2000)
        assertTrue(steeringAppearsInHistory(steering, listOf(old, persisted)))
        assertEquals(listOf(old, persisted, reply), mergeSteeringMessages(listOf(old, persisted, reply), listOf(steering)))
        assertFalse(steeringAppearsInHistory(steering, listOf(HermesMessage("user", "change direction", 10))))
    }
    @Test fun nativeRequestKeyConfirmsUndatedCodexSteeringWithoutMatchingAnotherRequest() {
        val sent = SteeringMessage("current-key", "session", "继续", emptySet(), 0)
        val other = HermesMessage("user", "继续", 10, requestKey = "other-key")
        val current = HermesMessage("user", "继续", 11, requestKey = sent.key)
        assertFalse(steeringAppearsInHistory(sent, listOf(other)))
        assertTrue(steeringAppearsInHistory(sent, listOf(current)))
        assertEquals(current, reconcileSteeringMessages(listOf(other, current), listOf(sent)).second.single().second)
    }
    @Test fun anUnconfirmedHermesSteerIsNotAcceptedFromSameTextAlone() {
        val pending = SteeringMessage("unknown-key", "session", "继续", emptySet(), 0,
            delivery = "发送状态待核对", timestamp = 10000)
        val old = HermesMessage("user", "继续", 20, timestamp = 11000)
        assertFalse(steeringAppearsInHistory(pending, listOf(old)))
        assertEquals(listOf(pending), reconcileSteeringMessages(listOf(old), listOf(pending)).first)
    }
    @Test fun mergedOutOfBandRowConfirmsEachSteeringLine() {
        // 网关把 turn 活跃期间的多条消息合并进一行（OOB 外壳，一行一条）。
        // 插话在网关队列排队 11 分钟后才合并入库——行时间远晚于发送时刻，不设上界。
        val merged = HermesMessage("user",
            "[OUT-OF-BAND USER MESSAGE — x]\n测试2\n测试3\n[/OUT-OF-BAND USER MESSAGE]",
            67348, timestamp = 11000 + 660_000)
        val second = SteeringMessage("k2", "session", "测试2", setOf(67340), 67340, "已送达", 10000)
        val third = SteeringMessage("k3", "session", "测试3", setOf(67340), 67340, "已送达", 10100)
        val (pending, acknowledged) = reconcileSteeringMessages(listOf(merged), listOf(second, third))
        assertEquals(emptyList<SteeringMessage>(), pending)
        assertEquals(setOf("k2", "k3"), acknowledged.map { it.first.key }.toSet())
        assertTrue(acknowledged.all { it.second.serverId == 67348L })
        // 同一文本行不能被两条同文本 steering 重复确认
        val dupeA = SteeringMessage("ka", "session", "测试2", setOf(67340), 67340, "已送达", 10000)
        val dupeB = SteeringMessage("kb", "session", "测试2", setOf(67340), 67340, "已送达", 10100)
        val (pendingDupe, ackDupe) = reconcileSteeringMessages(listOf(merged), listOf(dupeA, dupeB))
        assertEquals(1, ackDupe.size)
        assertEquals(1, pendingDupe.size)
    }
}
