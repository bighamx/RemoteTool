package com.chuckiehelper.mobile.nativeui

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceAuthenticationTest {
    @Test fun sameHostnameAndDifferentPortsKeepTheirDeviceTokensIsolated() {
        val authenticationA = DeviceAuthSession("token-A")
        val authenticationB = DeviceAuthSession("token-B")
        val apiA = NativeApi("http://host:8888", authentication = authenticationA)
        val apiB = NativeApi("http://host:9999", authentication = authenticationB)
        val chatA = apiA.withReadPolicy(noRetry = true)
        authenticationB.token = "renewed-B"
        assertEquals("access_token=token-A", apiA.cookie())
        assertEquals("access_token=token-A", chatA.cookie())
        assertEquals("access_token=renewed-B", apiB.cookie())
        authenticationA.token = "renewed-A"
        assertEquals("access_token=renewed-A", chatA.cookie())
        authenticationA.token = null
        assertEquals("", chatA.cookie())
        assertEquals("access_token=renewed-B", apiB.cookie())
    }
    @Test fun channelSwitchReusesDeviceTokenWithoutLoggingInAgain() = runBlocking {
        val saved = DeviceLogin("user", "password", "device-A-token")
        var logins = 0
        val result = DeviceAuthentication.recover("A", "A", { saved }, { listOf("stale-endpoint-token") },
            { it == "device-A-token" }, { logins++; "unexpected" }, { fail("Unchanged token need not be saved") })
        assertSame(saved, result)
        assertEquals(0, logins)
    }
    @Test fun expiredTokenUsesStoredPasswordAndPersistsTheReplacement() = runBlocking {
        var saved = DeviceLogin("user", "password", "expired")
        var logins = 0
        val result = DeviceAuthentication.recover("A", "A", { saved }, { emptyList() }, { false },
            { assertEquals("password", it.password); logins++; "renewed" }, { saved = it })
        assertEquals("renewed", result?.token)
        assertEquals("renewed", saved.token)
        assertEquals(1, logins)
    }
    @Test fun staleDestinationCookieDoesNotPreventMigratingAValidLegacyToken() = runBlocking {
        var saved: DeviceLogin? = null
        val attempted = mutableListOf<String>()
        DeviceAuthentication.recover("A", "A", { null }, { listOf("stale", "valid", "valid") },
            { attempted.add(it); it == "valid" }, { fail("Legacy migration must not prompt for password"); null }, { saved = it })
        assertEquals(listOf("stale", "valid"), attempted)
        assertEquals("valid", saved?.token)
    }
    @Test fun mismatchedIdentityNeverReadsOrTransmitsCredentials() = runBlocking {
        try {
            DeviceAuthentication.recover("A", "B", { fail("Must not read saved credentials"); null },
                { fail("Must not read cookies"); emptyList() }, { fail("Must not transmit token"); false },
                { fail("Must not transmit password"); null }, { fail("Must not save") })
            fail("Identity mismatch accepted")
        } catch (_: java.io.IOException) { }
    }
    @Test fun networkFailureCannotBecomeAPasswordPromptOrOverwriteSavedLogin() = runBlocking {
        val saved = DeviceLogin("user", "password", "valid")
        try {
            DeviceAuthentication.recover("A", "A", { saved }, { emptyList() }, { throw java.net.SocketTimeoutException() },
                { fail("Network failure must not retry login"); null }, { fail("Must not overwrite") })
            fail("Network failure swallowed")
        } catch (_: java.net.SocketTimeoutException) { }
    }
    @Test fun rejectedPasswordReturnsToLoginWithoutRepeatingMutatingRequests() = runBlocking {
        var attempts = 0
        val result = DeviceAuthentication.recover("A", "A", { DeviceLogin("user", "changed", "expired") },
            { emptyList() }, { false }, { attempts++; null }, { fail("Rejected login saved") })
        assertNull(result)
        assertEquals(1, attempts)
    }
}
