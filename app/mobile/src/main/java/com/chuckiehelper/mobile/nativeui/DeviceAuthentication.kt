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
}
