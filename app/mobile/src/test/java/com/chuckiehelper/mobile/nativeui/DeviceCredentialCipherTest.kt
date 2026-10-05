package com.chuckiehelper.mobile.nativeui

import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class DeviceCredentialCipherTest {
    private fun key() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    @Test fun encryptedCredentialsSurviveStorageWithoutExposingTheirPlainText() {
        val key = key()
        val plain = "password-and-login-token"
        val first = DeviceCredentialCipher.encrypt("A", plain, key)
        val second = DeviceCredentialCipher.encrypt("A", plain, key)
        assertFalse(first.contains(plain))
        assertNotEquals(first, second)
        assertEquals(plain, DeviceCredentialCipher.decrypt("A", first, key))
        assertEquals("DeviceLogin(redacted)", DeviceLogin("user", "password", "token").toString())
    }
    @Test fun copyingCiphertextToAnotherDeviceOrChangingItFailsAuthentication() {
        val key = key()
        val encrypted = DeviceCredentialCipher.encrypt("A", "secret", key)
        assertThrows(Exception::class.java) { DeviceCredentialCipher.decrypt("B", encrypted, key) }
        assertThrows(Exception::class.java) { DeviceCredentialCipher.decrypt("A", encrypted, key()) }
        assertThrows(Exception::class.java) { DeviceCredentialCipher.decrypt("A", encrypted.dropLast(4) + "AAAA", key) }
    }
}
