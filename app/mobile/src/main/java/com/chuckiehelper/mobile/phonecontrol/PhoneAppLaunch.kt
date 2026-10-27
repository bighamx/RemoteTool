package com.chuckiehelper.mobile.phonecontrol

import org.json.JSONObject

/** Only handles Xiaomi's cross-app launch dialog during an explicitly requested launch. */
internal object PhoneAppLaunch {
    const val SYSTEM_PACKAGE = "com.miui.securitycenter"
    private fun normalized(value: String) = value.replace(Regex("[\\s\u00a0“”\"「」『』，,？?]"), "")

    fun alwaysAllow(snapshot: JSONObject, caller: String, target: String): JSONObject? {
        if (caller.isBlank() || target.isBlank() || snapshot.optBoolean("truncated") ||
            snapshot.optString("foreground_package") != SYSTEM_PACKAGE) return null
        val rows = snapshot.optJSONArray("nodes") ?: return null
        val visible = (0 until rows.length()).map { rows.getJSONObject(it) }.filter {
            it.optString("package_name") == SYSTEM_PACKAGE && it.optBoolean("visible") && it.optBoolean("enabled") &&
                !it.optBoolean("password") && !it.optBoolean("occluded") && it.optBoolean("bounds_valid")
        }
        val candidates = visible.filter { normalized(it.optString("text")) == "始终允许" }
        if (candidates.size != 1 || !candidates.single().has("window_id")) return null
        val button = candidates.single()
        val texts = visible.filter { it.optInt("window_id", -1) == button.optInt("window_id") }
            .map { normalized(it.optString("text")) }.toSet()
        return button.takeIf {
            texts.containsAll(setOf("启动应用", "本次允许", "拒绝",
                normalized("${caller}想要打开${target}，是否允许？")))
        }
    }

    fun settle(targetPackage: String, caller: String, target: String, clock: () -> Long,
        checkActive: () -> Unit, observe: () -> JSONObject, click: (JSONObject, JSONObject) -> Boolean,
        pause: (JSONObject, Long) -> Unit): JSONObject {
        val until = clock() + 600L
        var clicked = false
        while (clock() < until) {
            checkActive()
            val snapshot = observe()
            if (snapshot.optString("foreground_package") == targetPackage)
                return result(clicked, true, if (clicked) "always_allowed" else "not_needed")
            if (!clicked) alwaysAllow(snapshot, caller, target)?.let { button ->
                checkActive()
                if (!click(snapshot, button)) return result(false, false, "click_rejected")
                clicked = true
            }
            pause(snapshot, (until - clock()).coerceIn(1, 50))
        }
        return result(clicked, false, if (clicked) "always_allowed" else "not_observed")
    }
    private fun result(clicked: Boolean, verified: Boolean, status: String) = JSONObject()
        .put("auto_approved", clicked).put("verified", verified).put("status", status)
}
