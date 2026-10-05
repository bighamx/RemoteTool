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
}
