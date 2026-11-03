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

internal fun updateJobActive(job: JSONObject?): Boolean = job?.updateText("phase") in setOf(
    "requested", "waiting", "pulling", "building", "downloading", "switching", "verifying", "rolling_back", "migrating")

internal fun updateJobTitle(job: JSONObject?): String = when (job?.updateText("phase")) {
    "requested" -> "准备更新"
    "waiting" -> "等待任务空闲"
    "pulling" -> "正在拉取源码"
    "downloading" -> "正在下载并校验"
    "building" -> "正在编译并重启"
    "switching", "migrating" -> "正在切换并重启"
    "verifying" -> "正在验证更新结果"
    "rolling_back" -> "正在恢复上一版本"
    "completed" -> "更新完成"
    "downloaded" -> "已下载，等待安装"
    "deferred" -> "上次更新已延后"
    "rolled_back" -> "更新失败，已回滚"
    "failed" -> "更新失败"
    "recovery_required" -> "更新异常，需要检查恢复结果"
    else -> ""
}

internal enum class SourceUpdateAction(val label: String) {
    COMPILE("编译并重启"), PULL("拉取并更新")
}

internal fun sourceUpdateAction(snapshot: JSONObject): SourceUpdateAction? {
    if (snapshot.updateText("mode") != "source" || !snapshot.optBoolean("canRebuild")) return null
    val check = snapshot.optJSONObject("check")
    val valid = check?.updateText("kind") == "source" && check.updateText("error").isBlank() &&
        (check.updateText("runningVersion").isBlank() || check.updateText("runningVersion") == snapshot.updateText("currentVersion"))
    if (valid) return when (check?.updateText("state")) {
        "up_to_date" -> null
        "source_changed", "rebuild_required" -> SourceUpdateAction.COMPILE
        "source_out_of_sync" -> if (snapshot.optBoolean("supportsSourcePull")) SourceUpdateAction.PULL else SourceUpdateAction.COMPILE
        else -> if (snapshot.optBoolean("supportsSourcePull")) null else SourceUpdateAction.COMPILE
    }
    return if (snapshot.optBoolean("supportsSourcePull")) null else SourceUpdateAction.COMPILE
}

internal fun updateCheckText(snapshot: JSONObject, checking: Boolean = false): String {
    if (checking) return "正在检查更新…"
    val check = snapshot.optJSONObject("check") ?: return "尚未检查更新"
    if (snapshot.updateText("mode") == "source" && check.updateText("kind") != "source") return "尚未核对源码版本，请点击立即检查"
    if (check.updateText("runningVersion").isNotBlank() && check.updateText("runningVersion") != snapshot.updateText("currentVersion")) return "版本已变化，请重新检查"
    check.updateText("error").takeIf { it.isNotBlank() }?.let { return "检查失败：" + updateErrorText(it) }
    if (snapshot.updateText("mode") == "source") return when (check.updateText("state")) {
        "up_to_date" -> "已是最新版本"
        "rebuild_required" -> "源码已是最新，需编译并重启服务端"
        "source_changed" -> "本地源码有未提交改动，将仅编译本地源码"
        "source_out_of_sync" -> "本地源码与远端不同，更新将先拉取源码"
        else -> check.updateText("message").ifBlank { "尚未核对源码版本" }
    }
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
