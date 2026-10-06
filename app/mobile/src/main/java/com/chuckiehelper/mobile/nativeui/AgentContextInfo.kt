package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

private fun contextTokens(value: Long) = when {
    value >= 1000000 -> String.format(Locale.ROOT, "%.2fM", value / 1000000.0)
    value >= 1000 -> String.format(Locale.ROOT, "%.1fk", value / 1000.0)
    else -> value.toString()
}

@Composable
fun AgentContextInfo(info: JSONObject?, runtime: String = "") {
    val available = info?.optBoolean("available") == true
    val tokens = info?.optLong("tokens") ?: 0
    val limit = info?.optLong("limit") ?: 0
    val ratio = if (available && limit > 0) tokens.toDouble() / limit else null
    val estimate = if (info?.optBoolean("estimated") == true) "约 " else ""
    val context = if (!available) "上下文 · 暂无数据" else
        "上下文 · $estimate${contextTokens(tokens)}" + if (ratio != null) " / ${contextTokens(limit)} · ${(ratio * 100).toInt()}%" else " · 未返回上限"
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp)) {
        Text(context + runtime.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(),
            modifier = Modifier.fillMaxWidth(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (ratio != null) LinearProgressIndicator(progress = { ratio.toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp).height(2.dp), drawStopIndicator = {},
            color = if (ratio >= .9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    }
}
