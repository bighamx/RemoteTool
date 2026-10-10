package com.chuckiehelper.mobile.nativeui

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class AgentHistoryCacheTest {
    @Test fun restartUsesSnapshotAndServerReplacementNeverKeepsStaleRows() {
        val root = Files.createTempDirectory("history-cache-test").toFile()
        try {
            val first = HermesMessage("user", "旧消息", 90, timestamp = null)
            val fresh = HermesMessage("assistant", "新消息", 2, timestamp = 1791590000000L, nativeTurnId = "turn")
            val cache = AgentHistoryCache(root, "codex-device")
            assertNull(cache.read("session"))
            assertTrue(root.listFiles()!!.isEmpty()) // Reading does not create directories.
            cache.write("session", listOf(first))
            assertEquals(listOf(first), AgentHistoryCache(root, "codex-device").read("session"))
            assertNull(AgentHistoryCache(root, "hermes-device").read("session"))
            assertNull(cache.read("another-session"))
            cache.write("session", listOf(fresh))
            assertEquals(listOf(fresh), cache.read("session"))
            cache.write("session", emptyList())
            assertEquals(emptyList<HermesMessage>(), cache.read("session"))
            cache.remove("session")
            assertNull(cache.read("session"))
        } finally { root.deleteRecursively() }
    }

    @Test fun corruptCacheIsIgnored() {
        val root = Files.createTempDirectory("corrupt-cache-test").toFile()
        try {
            val cache = AgentHistoryCache(root, "scope")
            cache.write("session", listOf(HermesMessage("user", "message", 1)))
            root.walkTopDown().first { it.extension == "json" }.writeText("{broken")
            assertNull(cache.read("session"))
        } finally { root.deleteRecursively() }
    }

    @Test fun activeNarrationSurvivesRefreshAndStopsAtTaskEndWithoutMovingNativeRows() {
        val history = listOf(HermesMessage("user", "继续", 90), HermesMessage("assistant", "历史回复", 2))
        val active = AssistantNarration("narration-current", "s", "实时旁白", 90, "继续", 100, run = "current")
        val stale = active.copy(key = "narration-old", text = "旧旁白", run = "old")
        val live = withActiveNarrations(history, listOf(stale, active), "current")
        assertEquals(history, live.take(history.size))
        assertEquals("实时旁白", live.last().text)
        assertEquals(live, withActiveNarrations(history, listOf(stale, active), "current"))
        assertEquals(history, withActiveNarrations(history, listOf(stale, active), null))
    }

    @Test fun matchingNativePartialDoesNotHideLongerStreamedText() {
        val id = "native-item"
        val history = listOf(HermesMessage("assistant", "正在检查", narrationMessageId(id)))
        val live = AssistantNarration("narration-current", "s", "正在检查运行中的服务", 0, "", 100,
            run = "current", messageId = id, streamed = true)
        assertEquals(listOf("正在检查运行中的服务"), withActiveNarrations(history, listOf(live), "current").map { it.text })
        assertEquals(history, withActiveNarrations(history, listOf(live), null))
    }

    @Test fun identicalTextInAnOlderTaskCannotHideCurrentNarration() {
        val history = listOf(HermesMessage("user", "旧任务", 90), HermesMessage("assistant", "正在检查", 2),
            HermesMessage("user", "当前任务", 70))
        val live = AssistantNarration("narration-current", "s", "正在检查", 70, "当前任务", 100, run = "current")
        assertEquals(history + HermesMessage("assistant", live.text, localKey = live.key, timestamp = live.timestamp, narration = true),
            withActiveNarrations(history, listOf(live), "current"))
    }

    @Test fun pendingRowsStaySeparateFromAuthoritativeHistory() {
        val native = listOf(HermesMessage("assistant", "没有时间", 90), HermesMessage("user", "最新", 2))
        val pending = HermesMessage("user", "尚待确认", localKey = "pending", timestamp = 1L)
        val result = appendPendingHistory(native, listOf(pending, pending), setOf("pending"))
        assertEquals(native, result.take(native.size))
        assertEquals(listOf(pending), result.drop(native.size))
    }

    @Test fun restoredOctoberSixPendingNeverAppendsAfterOctoberTenHistory() {
        val history = listOf(HermesMessage("user", "最新任务", 90, timestamp = 1791592000000L),
            HermesMessage("assistant", "最新回复", 2, timestamp = 1791592100000L))
        val old = HermesMessage("user", "旧的待核对消息", localKey = "old", timestamp = 1791260000000L,
            delivery = "发送状态待核对")
        repeat(3) { assertEquals(history, appendPendingHistory(history, listOf(old))) }
        assertEquals(history, appendPendingHistory(history, listOf(old), setOf("old")))
        assertEquals("发送状态待核对", old.delivery) // Visibility does not fabricate acceptance or delete the receipt.
    }

    @Test fun unknownHistoryTimesDoNotAuthorizeRestoredPendingPlacement() {
        val history = listOf(HermesMessage("assistant", "原始记录没有时间", 90))
        val pending = HermesMessage("user", "待核对", localKey = "restored", timestamp = 100L)
        assertEquals(history, appendPendingHistory(history, listOf(pending)))
        assertEquals(history + pending, appendPendingHistory(history, listOf(pending), setOf("restored")))
        assertEquals(history, appendPendingHistory(history, listOf(pending.copy(timestamp = null)), setOf("restored")))
    }

    @Test fun freshPendingRemainsVisibleUntilNativeHistoryAdvancesBeyondIt() {
        val old = HermesMessage("assistant", "上一轮", 90, timestamp = 100L)
        val pending = HermesMessage("user", "本次发送", localKey = "new", timestamp = 200L)
        assertEquals(listOf(old, pending), appendPendingHistory(listOf(old), listOf(pending)))
        val reply = HermesMessage("assistant", "下一条原生消息", 2, timestamp = 300L)
        assertEquals(listOf(old, reply), appendPendingHistory(listOf(old, reply), listOf(pending)))
    }
}
