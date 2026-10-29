package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject

internal data class CloudflareDnsProtection(val label: String, val warning: String, val managed: Boolean)

internal fun cloudflareDnsProtection(row: JSONObject): CloudflareDnsProtection? {
    val meta = row.optJSONObject("meta")
    val workerId = (meta?.opt("origin_worker_id") as? String)?.trim().orEmpty()
    val readOnly = meta?.opt("read_only") == true
    if (workerId.isNotEmpty()) {
        return CloudflareDnsProtection("Worker 管理", "此 DNS 记录由 Worker 管理${if (readOnly) "且为只读" else ""}，请前往 Cloudflare 的 Worker → 设置 → 域和路由修改绑定。直接编辑或删除可能导致域名无法访问。", readOnly)
    }
    if (readOnly) return CloudflareDnsProtection("Cloudflare 只读", "此记录由 Cloudflare 标记为只读，请前往对应服务的配置页面管理。", true)
    // 100:: is also used for ordinary proxied placeholder records: evidence of
    // a possible binding, not proof of ownership. Never infer ownership from IP alone.
    val content = row.optString("content").trim().lowercase()
    if (row.optString("type").equals("AAAA", true) && content in setOf("100::", "100:0:0:0:0:0:0:0", "0100:0000:0000:0000:0000:0000:0000:0000")) {
        return CloudflareDnsProtection("可能用于 Worker", "这条 AAAA 记录指向 100::，可能用于 Worker 自定义域名，也可能是普通代理占位记录。请先核对 Worker → 设置 → 域和路由；直接修改或删除可能导致域名无法访问。", false)
    }
    return null
}
