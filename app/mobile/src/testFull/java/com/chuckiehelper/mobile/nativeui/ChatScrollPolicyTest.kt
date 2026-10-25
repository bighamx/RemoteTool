package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class ChatScrollPolicyTest {
    @Test fun openingAndOwnMessagesScrollWhileReadingHistoryStaysPut() {
        assertTrue(shouldScrollChatToLatest(true, false, false))
        assertTrue(shouldScrollChatToLatest(false, true, false))
        assertTrue(shouldScrollChatToLatest(false, false, true))
        assertFalse(shouldScrollChatToLatest(false, false, false))
    }
}
