package com.chuckiehelper.mobile.nativeui

import java.io.IOException

class DeviceLogin(val username: String, val password: String, val token: String) {
    fun withToken(value: String) = DeviceLogin(username, password, value)
    override fun toString() = "DeviceLogin(redacted)"
}

class DeviceAuthSession(var token: String? = null)

/** No credential is read or transmitted until the endpoint identifies the device. */
object DeviceAuthentication {
    suspend fun recover(
        deviceId: String,
        observedId: String,
        read: () -> DeviceLogin?,
        legacyTokens: () -> List<String>,
        validate: suspend (String) -> Boolean,
        signIn: suspend (DeviceLogin) -> String?,
        save: (DeviceLogin) -> Unit,
    ): DeviceLogin? {
        if (observedId != deviceId) throw IOException("设备标识不匹配，属于另一台电脑")
        val saved = read()
        if (saved != null && saved.token.isNotBlank() && validate(saved.token)) return saved
        if (saved != null && saved.username.isNotBlank() && saved.password.isNotEmpty()) {
            val token = signIn(saved)
            if (!token.isNullOrBlank()) return saved.withToken(token).also(save)
        }
        for (token in legacyTokens().distinct().filter { it.isNotBlank() && it != saved?.token }) {
            if (validate(token)) return (saved ?: DeviceLogin("", "", "")).withToken(token).also(save)
        }
        return null
    }

    /**
     * Recover a session that is ALREADY connected when one of its requests comes back 401.
     * [recover] only runs while connecting; a token that expires mid-session therefore
     * surfaced "登录已过期" and required a manual reconnect. Returns a usable token, or null
     * when there is nothing to sign in with (the caller then reports login-required as before).
     *
     * [currentToken] is the token held right now and [failedToken] the one the failed request
     * carried: when another 401 already refreshed the session (currentToken moved on past
     * failedToken) we reuse it instead of signing in again, so a burst of parallel requests
     * costs one login, not one each.
     */
    suspend fun refresh(
        failedToken: String?,
        currentToken: String?,
        read: () -> DeviceLogin?,
        signIn: suspend (DeviceLogin) -> String?,
        save: (DeviceLogin) -> Unit,
    ): String? {
        if (!currentToken.isNullOrBlank() && currentToken != failedToken) return currentToken
        val saved = read() ?: return null
        if (saved.username.isBlank() || saved.password.isEmpty()) return null
        val token = signIn(saved) ?: return null
        save(saved.withToken(token))
        return token
    }
}
