package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

internal data class CloudflareTunnelConnection(
    val clientId: String,
    val locations: String,
    val originIps: String,
    val version: String,
    val arch: String,
)

// /connections returns connectors, each with its own conns array. Older servers
// may still return flattened connections; never use an edge connection id to disconnect.
internal fun cloudflareTunnelConnection(value: JSONObject): CloudflareTunnelConnection {
    fun text(row: JSONObject, vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
        (row.opt(key) as? String)?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) }
    }.orEmpty()
    val conns = value.optJSONArray("conns")
    val edges = if (conns != null) (0 until conns.length()).mapNotNull { conns.optJSONObject(it) } else listOf(value)
    fun edgeValues(vararg keys: String) = edges.map { text(it, *keys) }.filter { it.isNotEmpty() }.distinct().joinToString("、")
    return CloudflareTunnelConnection(
        clientId = if (conns != null || value.has("version") || value.has("arch")) text(value, "id", "clientId", "client_id")
            else text(value, "clientId", "client_id"),
        locations = edgeValues("coloName", "colo_name"),
        originIps = edgeValues("originIp", "origin_ip"),
        version = text(value, "version", "clientVersion", "client_version").ifEmpty { edgeValues("clientVersion", "client_version") },
        arch = text(value, "arch"),
    )
}
