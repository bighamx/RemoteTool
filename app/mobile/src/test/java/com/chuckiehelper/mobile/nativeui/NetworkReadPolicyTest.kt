package com.chuckiehelper.mobile.nativeui

import java.net.SocketException
import org.junit.Assert.*
import org.junit.Test

class NetworkReadPolicyTest {
    @Test fun readRetriesOnceWhileWritesAndProbesNeverReplay() {
        val abort = SocketException("Software caused connection abort")
        assertTrue(mayRetryRead("GET", false, 0, abort))
        assertFalse(mayRetryRead("GET", false, 1, abort))
        assertFalse(mayRetryRead("GET", true, 0, abort))
        listOf("POST", "PATCH", "PUT", "DELETE").forEach { assertFalse(mayRetryRead(it, false, 0, abort)) }
        assertFalse(mayRetryRead("GET", false, 0, LoginRequired()))
        assertFalse(mayRetryRead("GET", false, 0, ApiRequestFailure("missing", 404)))
        assertTrue(mayRetryRead("GET", false, 0, ApiRequestFailure("recycling", 503)))
    }
}
