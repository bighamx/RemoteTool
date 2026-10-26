package com.chuckiehelper.mobile.nativeui

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import org.junit.Assert.*
import org.junit.Test
import java.util.Date

class CloudIdTokenVerifierTest {
    private val key = RSAKeyGenerator(2048).keyID("test").generate()
    private val now = 1_000_000L
    private fun token(nonce: String = "nonce", audience: String = "client", issuer: String = CloudQrProtocol.BASE+"/", expires: Long = now+60_000): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).subject("subject").audience(audience)
            .issueTime(Date(now-1000)).expirationTime(Date(expires)).claim("nonce",nonce).build()
        return SignedJWT(JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test").build(),claims).apply { sign(RSASSASigner(key)) }.serialize()
    }
    @Test fun verifiesSignatureAndIdentity() {
        assertEquals("subject",CloudIdTokenVerifier.verify(token(),JWKSet(key.toPublicJWK()).toString(),"client","nonce",now))
    }
    @Test fun rejectsWrongNonceIssuerAudienceExpiryAndSignature() {
        val jwks = JWKSet(key.toPublicJWK()).toString()
        listOf(token(nonce="other"),token(audience="other"),token(issuer="https://evil.example/"),token(expires=now-60_000)).forEach {
            assertThrows(IllegalArgumentException::class.java) { CloudIdTokenVerifier.verify(it,jwks,"client","nonce",now) }
        }
        val other = RSAKeyGenerator(2048).keyID("test").generate()
        assertThrows(IllegalArgumentException::class.java) { CloudIdTokenVerifier.verify(token(),JWKSet(other.toPublicJWK()).toString(),"client","nonce",now) }
    }
}
