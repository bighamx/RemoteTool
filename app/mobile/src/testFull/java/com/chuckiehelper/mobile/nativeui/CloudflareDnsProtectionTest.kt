package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CloudflareDnsProtectionTest {
    @Test fun actualWorkerMetadataMarksAndProtectsRecord() {
        val guard = cloudflareDnsProtection(JSONObject("""{"type":"AAAA","content":"100::","meta":{"origin_worker_id":"worker-id","read_only":true}}"""))!!
        assertTrue(guard.managed)
        assertEquals("Worker 管理",guard.label)
    }
    @Test fun otherServiceReadOnlyAndWorkerOwnershipAreIndependent() {
        val readOnly = cloudflareDnsProtection(JSONObject("""{"meta":{"read_only":true}}"""))!!
        assertTrue(readOnly.managed)
        assertEquals("Cloudflare 只读",readOnly.label)
        val worker = cloudflareDnsProtection(JSONObject("""{"meta":{"origin_worker_id":"worker-id","read_only":false}}"""))!!
        assertEquals("Worker 管理",worker.label)
        assertFalse(worker.managed)
        assertNull(cloudflareDnsProtection(JSONObject("""{"meta":{"origin_worker_id":null,"source":"workers"}}""")))
    }
    @Test fun placeholderAddressWarnsWithoutClaimingOwnership() {
        val guard = cloudflareDnsProtection(JSONObject("""{"type":"AAAA","content":"100::","meta":{"source":"primary"}}"""))!!
        assertFalse(guard.managed)
        assertEquals("可能用于 Worker",guard.label)
    }
    @Test fun normalTunnelAndAutoAddedRecordsAreNotMisidentified() {
        assertNull(cloudflareDnsProtection(JSONObject("""{"type":"CNAME","content":"abc.cfargotunnel.com","meta":{"autoAdded":true}}""")))
        assertNull(cloudflareDnsProtection(JSONObject("""{"type":"AAAA","content":"2001:db8::1"}""")))
        assertNull(cloudflareDnsProtection(JSONObject("""{"type":"TXT","content":"100::"}""")))
    }
}
