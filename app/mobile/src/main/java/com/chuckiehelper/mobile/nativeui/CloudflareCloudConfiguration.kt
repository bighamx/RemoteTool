package com.chuckiehelper.mobile.nativeui

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Shared by both editions. CF credentials belong to a cloud subject, never a connected computer. */
internal class CloudflareCloudConfiguration(context: Context) {
    private val store = CloudAccountStore(context)
    private val subject get() = store.read("session")?.optString("subject").orEmpty()
    val canSync get() = subject.isNotBlank()
    fun read(): JSONArray = subject.takeIf { it.isNotBlank() }?.let { store.read("cf:$it")?.optJSONArray("accounts") } ?: JSONArray()
    fun write(rows: JSONArray) {
        val owner = subject
        require(owner.isNotBlank()) { "请先登录云账户" }
        validate(rows)
        store.save("cf:$owner", obj("accounts" to rows))
    }
    companion object {
        fun validate(rows: JSONArray): JSONArray {
            require(rows.length() <= 100) { "Cloudflare 账户过多" }
            val ids = mutableSetOf<String>()
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                require(row.getString("id").matches(Regex("[a-zA-Z0-9_-]{1,100}")) && ids.add(row.getString("id"))) { "Cloudflare 配置标识无效或重复" }
                require(row.getString("name").isNotBlank() && row.getString("name").length <= 80) { "Cloudflare 名称无效" }
                require(row.getString("accountId").matches(Regex("[a-fA-F0-9]{32}"))) { "Cloudflare Account ID 无效" }
                require(row.getString("apiToken").isNotBlank()) { "Cloudflare API Token 缺失" }
                for (key in listOf("apiToken", "accessKeyId", "secretAccessKey")) require(row.optString(key).length <= 4096) { "Cloudflare 凭据过长" }
                require(row.optString("accessKeyId").isBlank() == row.optString("secretAccessKey").isBlank()) { "R2 凭据需一起填写" }
            }
            return rows
        }
        /** Replace only the CF section; retain unrelated settings and forward-compatible fields. */
        fun merge(payload: JSONObject, rows: JSONArray): JSONObject = JSONObject(payload.toString()).apply {
            val settings = optJSONObject("settings") ?: JSONObject().also { put("settings", it) }
            settings.put("cloudflare", JSONObject().put("accounts", validate(JSONArray(rows.toString()))))
        }
    }
}
