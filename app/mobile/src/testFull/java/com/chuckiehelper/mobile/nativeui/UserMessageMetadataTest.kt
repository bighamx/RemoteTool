package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class UserMessageMetadataTest {
    @Test fun missingServerTimeDoesNotEraseAnAcknowledgedUploadTime() {
        val local = HermesMessage("user", "picture", 91, localKey = "sent", timestamp = 1000L)
        val rows = retainUserMessageMetadata(listOf(local.copy(timestamp = null, localKey = null)), listOf(local))
        assertEquals(local, rows.single())
        assertEquals(2000L, retainUserMessageMetadata(listOf(local.copy(timestamp = 2000L)), listOf(local)).single().timestamp)
    }
    @Test fun identityDoesNotComeFromMatchingTextOrNumericIdOrdering() {
        val old = HermesMessage("user", "same", 99, timestamp = 1000L)
        val newer = HermesMessage("user", "same", 2)
        assertEquals(listOf(newer, old), retainUserMessageMetadata(listOf(newer, old), listOf(old)))
        assertNull(retainUserMessageMetadata(listOf(old.copy(text = "different", timestamp = null)), listOf(old)).single().timestamp)
    }
}
