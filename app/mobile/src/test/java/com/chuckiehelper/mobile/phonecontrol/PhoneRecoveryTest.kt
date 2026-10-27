package com.chuckiehelper.mobile.phonecontrol

import org.junit.Assert.*
import org.junit.Test

class PhoneRecoveryTest {
    @Test fun fiveMinuteBoundaryAndRunningCallsUseCompletionTime() {
        var now = 0L
        val sessions = PhoneMcpSessions { now }
        val first = sessions.create("Agent")
        now = 299_999
        assertNotNull(sessions.get(first.id))
        now = 599_999
        assertNull(sessions.get(first.id))
        val resumed = sessions.create("Agent", first.resumeToken)
        sessions.withClient(resumed) {
            now += 600_000
            assertNotNull(sessions.get(resumed.id))
        }
        now += 299_999
        assertNotNull(sessions.get(resumed.id))
        now += 300_000
        assertNull(sessions.get(resumed.id))
    }
    @Test fun recoveryReplacesTransportButPreservesOwnerAndFencesOldClient() {
        val sessions = PhoneMcpSessions { 1000 }
        val old = sessions.create("Agent")
        val resumed = sessions.create("Another name", old.resumeToken)
        assertNotEquals(old.id, resumed.id)
        assertEquals(old.owner, resumed.owner)
        assertEquals("Another name", resumed.name)
        assertEquals(2, resumed.generation)
        assertNull(sessions.get(old.id))
        assertEquals("session_replaced", assertThrows(PhoneToolError::class.java) { sessions.withClient(old) { fail("must not run") } }.code)
        assertEquals("ok", sessions.withClient(resumed) { "ok" })
    }
    @Test fun activeOperationPreventsTakeoverAndUserDisconnectPreventsRecovery() {
        val sessions = PhoneMcpSessions { 1000 }
        val old = sessions.create("Agent")
        sessions.withClient(old) {
            assertEquals("device_busy", assertThrows(PhoneToolError::class.java) { sessions.create("Agent", old.resumeToken) }.code)
        }
        assertEquals("connection_closed_by_user", assertThrows(PhoneToolError::class.java) { sessions.create("Agent", old.resumeToken) { false } }.code)
        assertNotNull(sessions.get(old.id))
    }
    @Test fun sameNameAndUnknownSecretDoNotRecoverAnotherOwner() {
        val sessions = PhoneMcpSessions { 1000 }
        val old = sessions.create("Agent")
        assertNotEquals(old.owner, sessions.create("Agent").owner)
        assertEquals("resume_unknown", assertThrows(PhoneToolError::class.java) { sessions.create("Agent", "made-up") }.code)
        sessions.remove(old.id)
        assertEquals("resume_unknown", assertThrows(PhoneToolError::class.java) { sessions.create("Agent", old.resumeToken) }.code)
    }
    @Test fun transportExpiryDoesNotLoseRecoverableLogicalIdentity() {
        var now = 1000L
        val sessions = PhoneMcpSessions { now }
        val old = sessions.create("Agent")
        now += 31 * 60_000L
        assertNull(sessions.get(old.id))
        assertEquals(old.owner, sessions.create("Agent", old.resumeToken).owner)
        now += 25 * 60 * 60_000L
        assertEquals("resume_unknown", assertThrows(PhoneToolError::class.java) { sessions.create("Agent", old.resumeToken) }.code)
    }
    @Test fun cachedOwnerCleanupSurvivesTransportReplacementAndCannotCloseAnotherOwner() {
        var now = 1000L
        val sessions = PhoneMcpSessions { now }
        val first = sessions.create("A"); val other = sessions.create("B")
        val resumed = sessions.create("A", first.resumeToken)
        assertNull(sessions.remove(first.id))
        assertNotNull(sessions.get(resumed.id))
        sessions.withClient(resumed) {
            assertEquals("device_busy", assertThrows(PhoneToolError::class.java) { sessions.removeByResumeToken(first.resumeToken) }.code)
        }
        now += 31 * 60_000L
        assertEquals(first.owner, sessions.removeByResumeToken(first.resumeToken)!!.owner)
        assertNull(sessions.removeByResumeToken(first.resumeToken))
        assertEquals(other.owner, sessions.create("B", other.resumeToken).owner)
    }
}
