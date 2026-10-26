package com.chuckiehelper.mobile.nativeui

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.SignedJWT
import java.security.MessageDigest

/** Never follows token-supplied jku/x5u URLs or accepts symmetric/unsigned tokens. */
internal object CloudIdTokenVerifier {
    fun verify(raw: String, jwks: String, client: String, nonce: String, now: Long = System.currentTimeMillis()): String {
        require(raw.length in 1..32768 && jwks.length <= 1024*1024 && nonce.isNotBlank()) { "登录令牌无效" }
        val jwt = SignedJWT.parse(raw)
        val alg = jwt.header.algorithm
        require(alg in setOf(JWSAlgorithm.RS256,JWSAlgorithm.PS256,JWSAlgorithm.ES256)) { "不支持此登录签名算法" }
        val verified = JWKSet.parse(jwks).keys.filter { key ->
            (jwt.header.keyID == null || key.keyID == jwt.header.keyID) && (key.keyUse == null || key.keyUse == KeyUse.SIGNATURE) &&
                (key.algorithm == null || key.algorithm == alg)
        }.any { key -> runCatching {
            when {
                key is RSAKey && alg in setOf(JWSAlgorithm.RS256,JWSAlgorithm.PS256) -> jwt.verify(RSASSAVerifier(key.toRSAPublicKey()))
                key is ECKey && alg == JWSAlgorithm.ES256 -> jwt.verify(ECDSAVerifier(key.toECPublicKey()))
                else -> false
            }
        }.getOrDefault(false) }
        require(verified) { "登录令牌签名校验失败" }
        val claims = jwt.jwtClaimsSet
        require(claims.issuer == CloudQrProtocol.BASE+"/" && client in claims.audience && claims.subject?.isNotBlank() == true) { "登录令牌身份校验失败" }
        val azp = claims.getStringClaim("azp")
        require((claims.audience.size == 1 || azp == client) && (azp == null || azp == client)) { "登录令牌客户端不匹配" }
        require(claims.expirationTime != null && claims.issueTime != null && claims.expirationTime.time > now-30_000 &&
            claims.issueTime.time <= now+30_000 && claims.expirationTime.after(claims.issueTime) &&
            (claims.notBeforeTime == null || claims.notBeforeTime.time <= now+30_000)) { "登录令牌已过期或尚未生效" }
        val actualNonce = claims.getStringClaim("nonce").orEmpty()
        require(MessageDigest.isEqual(nonce.toByteArray(Charsets.UTF_8),actualNonce.toByteArray(Charsets.UTF_8))) { "登录令牌 nonce 校验失败" }
        return claims.subject
    }
}
