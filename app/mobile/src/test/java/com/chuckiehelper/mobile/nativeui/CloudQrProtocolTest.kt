package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CloudQrProtocolTest {
    private val code = CloudQrProtocol.secret()
    private fun url(flow: String = "offer") = "https://api.xbyham.com/QrLogin/Scan#v=1&flow=$flow&code=$code"
    @Test fun validDirectionsAndCanonicalSecrets() {
        assertEquals(code, CloudQrProtocol.parse(url(), "offer").code)
        assertEquals("receive", CloudQrProtocol.parse(url("receive"), "receive").flow)
        assertEquals(43, CloudQrProtocol.challenge(code).length)
        assertTrue(CloudQrProtocol.validSecret(code))
        assertFalse(CloudQrProtocol.validSecret(code + "="))
        assertFalse(CloudQrProtocol.validSecret("!".repeat(43)))
        assertFalse(CloudQrProtocol.parse(url(), "offer").toString().contains(code))
        assertNotEquals(code, CloudQrProtocol.secret())
    }
    @Test fun untrustedAndAmbiguousQrCodesAreRejected() {
        val invalid = listOf(url().replace("https:", "http:"), url().replace("api.xbyham.com", "evil.example"),
            url().replace("api.xbyham.com", "api.xbyham.com@evil.example"),
            url().replace("/Scan#", "/Scan?x=1#"), url().replace("/Scan#", "/Scan/#"),
            url()+"&code=$code", url()+"&%76=1", url()+"&extra=yes", url().replace("v=1", "v=2"),
            url().replace("api.xbyham.com", "api.xbyham.com:8443"), url("receive"))
        invalid.forEach { raw -> assertThrows(IllegalArgumentException::class.java) { CloudQrProtocol.parse(raw,"offer") } }
    }
    @Test fun expiryIsBoundedAndConfirmationPreservesLeadingZeroes() {
        val now = 1_000_000L
        val packet = JSONObject().put("flow","receive").put("status","pending").put("request_id","a".repeat(32))
            .put("qr_ticket",code).put("confirmation_code","000123").put("qr_payload",url("receive"))
            .put("expires_at",Instant.ofEpochMilli(now+200_000).toString()).put("expires_in",120).put("interval",3)
        val pending = CloudQrPending.parse(packet,"receive",code,code,now)
        assertEquals(now+120_000,pending.expiresAt)
        assertEquals("000123",pending.confirmationCode)
        packet.put("expires_in",181)
        assertThrows(IllegalArgumentException::class.java) { CloudQrPending.parse(packet,"receive",code,code,now) }
    }
    @Test fun discoveryEndpointsCannotRedirectOutsideIssuer() {
        assertEquals("/.well-known/jwks",CloudQrProtocol.trustedPath("https://api.xbyham.com/.well-known/jwks"))
        listOf("http://api.xbyham.com/jwks", "https://evil.example/jwks", "https://api.xbyham.com/jwks?x=1").forEach {
            assertThrows(IllegalArgumentException::class.java) { CloudQrProtocol.trustedPath(it) }
        }
    }
}
