package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class ChatMessageKeysTest {
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
