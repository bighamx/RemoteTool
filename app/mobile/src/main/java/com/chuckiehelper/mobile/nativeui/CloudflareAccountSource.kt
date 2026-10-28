package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray

internal interface CloudflareAccountSource {
    val subject: String
    fun cachedCloudflareAccounts(): JSONArray
    suspend fun cloudflareAccounts(): JSONArray
    suspend fun updateCloudflareAccounts(transform: (JSONArray, Boolean) -> JSONArray)
}
