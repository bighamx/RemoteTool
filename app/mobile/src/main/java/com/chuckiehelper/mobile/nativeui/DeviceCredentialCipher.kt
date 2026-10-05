package com.chuckiehelper.mobile.nativeui

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object DeviceCredentialCipher {
    fun encrypt(deviceId: String, plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(deviceId.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(cipher.iv) + "." +
            Base64.getEncoder().encodeToString(cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(deviceId: String, encrypted: String, key: SecretKey): String {
        val parts = encrypted.split('.', limit = 2)
        require(parts.size == 2)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])))
        cipher.updateAAD(deviceId.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), Charsets.UTF_8)
    }
}
