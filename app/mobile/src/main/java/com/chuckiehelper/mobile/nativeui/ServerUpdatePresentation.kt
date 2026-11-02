package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal fun JSONObject.updateText(key: String): String =
    if (isNull(key)) "" else optString(key, "").takeUnless { it == "null" }.orEmpty()

internal fun updateErrorText(value: String): String = value.replace(
    Regex("Response status code does not indicate success: (\\d+) \\([^)]+\\)\\.?"), "服务端请求失败（HTTP $1）")

internal fun updateCheckText(snapshot: JSONObject, checking: Boolean = false): String {
    if (checking) return "正在检查更新…"
    val check = snapshot.optJSONObject("check") ?: return "尚未检查更新"
    if (snapshot.updateText("mode") == "source" && check.updateText("kind") != "source") return "尚未核对源码版本，请点击立即检查"
    if (check.updateText("runningVersion").isNotBlank() && check.updateText("runningVersion") != snapshot.updateText("currentVersion")) return "版本已变化，请重新检查"
    check.updateText("error").takeIf { it.isNotBlank() }?.let { return "检查失败：" + updateErrorText(it) }
    if (snapshot.updateText("mode") == "source") return check.updateText("message").ifBlank { "尚未核对源码版本" }
    return if (check.optBoolean("available")) "可更新至 " + check.updateText("version") else "已是最新版本"
}

internal fun updateWindowText(policy: JSONObject?, zone: ZoneId = ZoneId.systemDefault()): String {
    val start = policy?.optInt("startHourUtc", 18) ?: 18
    val end = policy?.optInt("endHourUtc", 22) ?: 22
    if (start == end) return "自动安装时段：全天"
    fun local(hour: Int): String = LocalDate.now(ZoneOffset.UTC).atTime(hour.coerceIn(0,23), 0)
        .atZone(ZoneOffset.UTC).withZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
    return "自动安装时段：${local(start)}–${local(end)}（本地时间）"
}
