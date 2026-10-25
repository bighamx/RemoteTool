package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class ChatMessageKeysTest {
    @Test fun activeFinalKeepsItsRowAndPlainRendererWhenHistoryArrives() {
        val stream = ChatTextStream("run-a", "item-a", "正在生成完整回复", 1000L)
        val pending = chatMessageItems(emptyList(), stream).single()
        assertTrue(pending.streaming)
        assertEquals(0L, pending.message.serverId) // Saved-only actions stay disabled.
        val history = HermesMessage("assistant", "正在生成", narrationMessageId("item-a"))
        val synced = chatMessageItems(listOf(history), stream).single()
        assertEquals(pending.key, synced.key)
        assertEquals(stream.text, synced.message.text)
        assertTrue(synced.streaming)
        val completed = chatMessageItems(listOf(history), stream.copy(completed = true)).single()
        assertEquals(pending.key, completed.key)
        assertFalse(completed.streaming)
        assertEquals(pending.key, chatMessageItems(listOf(history.copy(text = stream.text))).single().key)
    }

    @Test fun commentaryAndFinalSyncKeepIdentityWhenReplacedByNativeHistory() {
        val stream = ChatTextStream("run-a", "item-a", "进度", 1000L, narration = true)
        val original = chatMessageItems(emptyList(), stream).single()
        assertTrue(original.message.narration)
        for (localKey in listOf("narration-run-a-item-item-a", "final-sync-run-a")) {
            val aliases = mapOf(localKey to stream.identity)
            val local = HermesMessage("assistant", "进度", localKey = localKey, narration = true)
            val native = local.copy(serverId = narrationMessageId("item-a"), localKey = null)
            assertEquals(original.key, chatMessageItems(listOf(local), assistantIdentities = aliases).single().key)
            assertEquals(original.key, chatMessageItems(listOf(native), assistantIdentities = aliases).single().key)
        }
    }

    @Test fun staleStreamCannotTruncateNewerHistoryOrChangeOtherRows() {
        val older = HermesMessage("assistant", "旧回复", 1)
        val newer = HermesMessage("assistant", "完整回复", narrationMessageId("item-a"))
        val stream = ChatTextStream("run-a", "item-a", "完整", 1000L)
        val rows = chatMessageItems(listOf(older, newer), stream)
        assertEquals(listOf("旧回复", "完整回复"), rows.map { it.message.text })
        assertFalse(rows[0].streaming)
        assertTrue(rows[1].streaming)
    }

    @Test fun insertionAndAttachmentRefreshDoNotChangeExistingIdentity() {
        val message = HermesMessage("assistant", "图片", serverId = 9)
        val key = chatMessageKeys(listOf(message)).single()
        assertEquals(key, chatMessageKeys(listOf(HermesMessage("system", "压缩完成", localKey = "compact"), message))[1])
        assertEquals(key, chatMessageKeys(listOf(message.copy(attachments = listOf(obj("id" to "image"))))).single())
    }
    @Test fun duplicateAndLocalRowsCannotCrashTheLazyList() {
        val message = HermesMessage("user", "继续", serverId = 9)
        val keys = chatMessageKeys(listOf(message, message, message.copy(localKey = "optimistic")))
        assertEquals(3, keys.distinct().size)
    }
    @Test fun pendingEmptyLayoutRemainsEmptyWhileHistoryArrives() {
        var history = emptyList<HermesMessage>()
        val oldKeys = chatMessageKeys(history)
        val queuedLayout = chatMessageItems(history)
        history = (1L..500L).map { HermesMessage("assistant", "历史 $it", serverId = it) }
        // The old UI read the updated history lazily but indexed keys captured
        // while history was empty. Reproduce that exact out-of-bounds failure.
        assertThrows(IndexOutOfBoundsException::class.java) { oldKeys[history.indices.first()] }
        assertTrue(queuedLayout.isEmpty())
        val nextLayout = chatMessageItems(history)
        assertEquals(500, nextLayout.size)
        nextLayout.forEach { assertEquals("server:${it.message.serverId}:0", it.key) }
    }
    @Test fun queuedLayoutKeepsItsMessagesAndKeysAcrossAppendAndSessionSwitch() {
        var history = listOf(HermesMessage("user", "第一条", serverId = 1))
        val queuedLayout = chatMessageItems(history)
        history = history + HermesMessage("assistant", "新回复", serverId = 2)
        assertEquals(2, chatMessageItems(history).size)
        history = listOf(HermesMessage("user", "另一会话", serverId = 3))
        assertEquals("server:3:0", chatMessageItems(history).single().key)
        assertEquals("server:1:0", queuedLayout.single().key)
        assertEquals("第一条", queuedLayout.single().message.text)
    }
    @Test fun modifyingSourceCollectionCannotDetachKeysFromCapturedMessages() {
        val history = mutableListOf(HermesMessage("user", "第一条", serverId = 1))
        val queuedLayout = chatMessageItems(history)
        history.clear()
        history.add(HermesMessage("assistant", "替换", serverId = 9))
        assertEquals(1L, queuedLayout.single().message.serverId)
        assertEquals("server:1:0", queuedLayout.single().key)
    }
}
