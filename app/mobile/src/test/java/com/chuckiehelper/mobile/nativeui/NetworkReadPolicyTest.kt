package com.chuckiehelper.mobile.nativeui

import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test

class NetworkReadPolicyTest {
    @Test fun codexThreadErrorExplainsChannelAndKeepsOtherErrors() {
        assertEquals("原任务连接已释放，本次消息未写入。请刷新会话后重新发送。",
            connectionFailureMessage(ApiRequestFailure("thread not found: abc", 409)))
        assertEquals("请求无效", connectionFailureMessage(ApiRequestFailure("请求无效", 400)))
    }
    @Test fun readRetriesOnceWhileWritesAndProbesNeverReplay() {
        val abort = SocketException("Software caused connection abort")
        assertTrue(mayRetryRead("GET", false, 0, abort))
        assertFalse(mayRetryRead("GET", false, 1, abort))
        assertFalse(mayRetryRead("GET", true, 0, abort))
        listOf("POST", "PATCH", "PUT", "DELETE").forEach { assertFalse(mayRetryRead(it, false, 0, abort)) }
        assertFalse(mayRetryRead("GET", false, 0, LoginRequired()))
        assertFalse(mayRetryRead("GET", false, 0, ApiRequestFailure("missing", 404)))
        assertTrue(mayRetryRead("GET", false, 0, ApiRequestFailure("recycling", 503)))
        assertFalse(mayRetryRead("GET", false, 0, abort, noRetry = true))
    }
    @Test fun writeAcknowledgementSeparatesRejectionFromLostAcknowledgement() {
        assertTrue(writeWasRejected(ApiRequestFailure("未写入", 503, delivery = "rejected")))
        assertFalse(writeWasRejected(ApiRequestFailure("等待核对", 409, delivery = "unknown")))
        assertFalse(writeWasRejected(ApiRequestFailure("超时", 504)))
        assertFalse(writeWasRejected(SocketException("Disconnected")))
        assertTrue(writeWasRejected(ApiRequestFailure("旧任务", 409, "run_stale")))
        assertTrue(staleRunFailure(ApiRequestFailure("旧任务", 409, "run_stale")))
    }
}
