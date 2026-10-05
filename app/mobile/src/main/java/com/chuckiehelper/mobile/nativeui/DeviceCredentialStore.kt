package com.chuckiehelper.mobile.nativeui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import org.json.JSONObject

/** Passwords/tokens are encrypted with a non-exportable Android Keystore key. */
class DeviceCredentialStore(context: Context) {
    private val prefs = context.getSharedPreferences("device-credentials", Context.MODE_PRIVATE)
    private val cache = mutableMapOf<String, DeviceLogin?>()
    private val alias = "ChuckieHelper.DeviceCredentials.v1"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized fun read(deviceId: String): DeviceLogin? {
        if (cache.containsKey(deviceId)) return cache[deviceId]
        val encrypted = prefs.getString(deviceId, null) ?: return null
        val result = runCatching {
            val row = JSONObject(DeviceCredentialCipher.decrypt(deviceId, encrypted, key()))
            DeviceLogin(row.optString("username"), row.optString("password"), row.optString("token"))
        }.getOrNull()
        cache[deviceId] = result
        return result
    }

    @Synchronized fun save(deviceId: String, login: DeviceLogin) {
        val plain = JSONObject().put("username", login.username).put("password", login.password).put("token", login.token).toString()
        val encrypted = DeviceCredentialCipher.encrypt(deviceId, plain, key())
        check(prefs.edit().putString(deviceId, encrypted).commit()) { "无法保存此设备的登录凭据" }
        cache[deviceId] = login
    }

    @Synchronized fun remove(deviceId: String) {
        prefs.edit().remove(deviceId).apply()
        cache.remove(deviceId)
    }
}
