package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject

internal fun CfLocation.key(accountId: String): String = JSONArray(listOf(accountId, operation,
    JSONObject().also { sorted -> args.keys().asSequence().sorted().forEach { sorted.put(it, args.opt(it)) } }.toString())).toString()

internal val CfLocation.displayTitle: String get() = "${when(operation.substringBefore('.')) {
    "dns", "zones" -> "DNS"; "r2" -> "R2"; "d1" -> "D1"; "tunnels" -> "Tunnels"; else -> "Cloudflare"
}} - $title"

/** Immutable JSON copies prevent an editor or a background refresh mutating cached rows in place. */
internal data class CfListingSnapshot(val items: String = "[]", val results: String = "[]", val detail: String? = null,
    val hasMore: Boolean = false, val page: Int = 1, val cursor: String? = null) {
    val chars get() = items.length + results.length + (detail?.length ?: 0)
}

internal class CloudflareListingCache(private val capacity: Int = 48, private val maxChars: Int = 4_000_000) {
    var selectedAccountId: String? = null
    var selectedTab = 0
    private val rows = LinkedHashMap<String, CfListingSnapshot>(16, .75f, true)
    fun get(key: String): CfListingSnapshot? = rows[key]
    fun put(key: String, value: CfListingSnapshot) {
        rows.remove(key)
        if (value.chars > maxChars) return
        rows[key] = value
        while (rows.size > capacity || rows.values.sumOf { it.chars } > maxChars) rows.remove(rows.keys.first())
    }
    fun clear() = rows.clear()
}

/** Retain bounded resource snapshots across page visits; never share them between cloud users. */
internal object CloudflarePageCache {
    private var owner: String? = null
    private var listings = CloudflareListingCache()
    fun forOwner(subject: String): CloudflareListingCache {
        if (owner != subject) { listings.clear(); listings = CloudflareListingCache(); owner = subject }
        return listings
    }
}

internal fun cfListingSnapshot(operation: String, data: Any?, page: Int = 1, previous: List<JSONObject> = emptyList()): CfListingSnapshot = when(operation) {
    "d1.tables" -> CfListingSnapshot(items = JSONArray(CloudflareModel.queryRows(data)).toString())
    "d1.rows" -> CfListingSnapshot(results = (data as? JSONArray ?: JSONArray()).toString(), hasMore = CloudflareModel.queryRows(data).size == 100)
    "tunnels.detail" -> CfListingSnapshot(detail = (data as? JSONObject)?.toString())
    else -> {
        val response = data as? JSONObject ?: JSONObject()
        val next = response.optString("cursor").takeIf { it.isNotBlank() && it != "null" }
        val added = response.optJSONArray("items")?.objects().orEmpty()
        val items = (previous + added).distinctBy { it.optString("key", it.optString("id", it.optString("uuid", it.optString("name")))) }
        CfListingSnapshot(items = JSONArray(items).toString(), hasMore = response.optBoolean("hasMore") || next != null, page = page, cursor = next)
    }
}
