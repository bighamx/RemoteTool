package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

internal fun compactTokenCount(value: Long): String {
    if (value in -999L..999L) return value.toString()
    val units = arrayOf("", "K", "M", "B", "T")
    var size = kotlin.math.abs(value.toDouble())
    var unit = 0
    while (size >= 1000 && unit < units.lastIndex) { size /= 1000; unit++ }
    var rounded = kotlin.math.round(size * 10) / 10
    if (rounded >= 1000 && unit < units.lastIndex) { rounded /= 1000; unit++ }
    val format = if (rounded % 1.0 == 0.0) "%.0f%s" else "%.1f%s"
    return (if (value < 0) "-" else "") + String.format(Locale.ROOT, format, rounded, units[unit])
}

@Composable
fun AgentContextInfo(info: JSONObject?, runtime: String = "", onClick: (() -> Unit)? = null) {
    val available = info?.optBoolean("available") == true
    val tokens = info?.optLong("tokens") ?: 0
    val limit = info?.optLong("limit") ?: 0
    val ratio = if (available && limit > 0) tokens.toDouble() / limit else null
    val estimate = if (info?.optBoolean("estimated") == true) "约 " else ""
    val context = if (!available) (info?.optString("message")?.takeIf { it.isNotBlank() } ?: "暂无数据") else
        "$estimate${compactTokenCount(tokens)}" + if (ratio != null) " / ${compactTokenCount(limit)} · ${(ratio * 100).toInt()}%" else " · 未返回上限"
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp).let { modifier ->
        if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    }) {
        Text(context + runtime.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ratio != null) LinearProgressIndicator(progress = { ratio.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(2.dp), drawStopIndicator = {},
            color = if (ratio >= .9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}
