package com.chuckiehelper.mobile.phonecontrol

import org.junit.Assert.*
import org.junit.Test

class PhoneRecoveryTest {
    @Test fun recoveryReplacesTransportButPreservesOwnerAndFencesOldClient() {
        val sessions = PhoneMcpSessions { 1000 }
        val old = sessions.create("Agent")
        val resumed = sessions.create("Another name", old.resumeToken)
        assertNotEquals(old.id, resumed.id)
        assertEquals(old.owner, resumed.owner)
        assertEquals(old.name, resumed.name)
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
        assertEquals("session_recovery_failed", assertThrows(PhoneToolError::class.java) { sessions.create("Agent", "made-up") }.code)
        sessions.remove(old.id)
        assertEquals("session_recovery_failed", assertThrows(PhoneToolError::class.java) { sessions.create("Agent", old.resumeToken) }.code)
    }
    @Test fun transportExpiryDoesNotLoseRecoverableLogicalIdentity() {
        var now = 1000L
        val sessions = PhoneMcpSessions { now }
        val old = sessions.create("Agent")
        now += 31 * 60_000L
        assertNull(sessions.get(old.id))
        assertEquals(old.owner, sessions.create("Agent", old.resumeToken).owner)
        now += 25 * 60 * 60_000L
        assertEquals("session_recovery_failed", assertThrows(PhoneToolError::class.java) { sessions.create("Agent", old.resumeToken) }.code)
    }
}
