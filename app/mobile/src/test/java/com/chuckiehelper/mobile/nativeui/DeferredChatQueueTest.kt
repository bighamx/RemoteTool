package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class DeferredChatQueueTest {
    @Test fun runningApprovalOrUncertainSubmissionCannotBeBypassedByQueue() {
        assertTrue(queueMayDispatch(false,false,false,false,false))
        for(index in 0..4) {
            val flags=BooleanArray(5);flags[index]=true
            assertFalse(queueMayDispatch(flags[0],flags[1],flags[2],flags[3],flags[4]))
        }
    }
    @Test fun durableQueuePreservesFifoSessionAndAttachmentsAcrossRestart() {
        val rows=listOf(QueuedChatMessage("a","session-a","first",listOf(JSONObject("{\"id\":\"file\",\"name\":\"image.jpg\"}")), accountId="account-a"),
            QueuedChatMessage("b","session-b","second",emptyList(),"native-b","native","等待任务结束",accountId="account-b"))
        val restored=restoreQueuedMessages(queuedMessagesJson(rows))
        assertEquals(listOf("a","b"),restored.map { it.key })
        assertEquals(listOf("session-a","session-b"),restored.map { it.session })
        assertEquals("file",restored.first().files.single().getString("id"))
        assertEquals("native-b",restored.last().nativeId)
        assertEquals(listOf("account-a","account-b"),restored.map { it.accountId })
    }
    @Test fun interruptedDispatchNeverAutomaticallyReplaysAfterAppRestart() {
        val row=QueuedChatMessage("key","s","once",emptyList(),mode="local",status="发送中")
        assertEquals("发送结果待核对",restoreQueuedMessages(queuedMessagesJson(listOf(row))).single().status)
    }
}
