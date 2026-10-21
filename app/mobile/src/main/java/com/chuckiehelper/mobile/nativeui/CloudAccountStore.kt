package com.chuckiehelper.mobile.nativeui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Separate namespace and Keystore key from computer login credentials. */
internal class CloudAccountStore(context: Context) {
    private val prefs = context.getSharedPreferences("cloud-account", 0)
    private val alias = "RemoteTool.CloudAccount.v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }
    fun read(name: String): JSONObject? {
        val encrypted = prefs.getString(name, null) ?: return null
        return runCatching { JSONObject(DeviceCredentialCipher.decrypt(name, encrypted, key())) }.getOrNull()
    }
    fun save(name: String, value: JSONObject) {
        check(prefs.edit().putString(name, DeviceCredentialCipher.encrypt(name, value.toString(), key())).commit()) { "无法安全保存云账户" }
    }
    fun remove(name: String) { check(prefs.edit().remove(name).commit()) { "无法清除云账户" } }
}
