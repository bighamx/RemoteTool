package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.util.Locale

private fun contextTokens(value: Long) = when {
    value >= 1000000 -> String.format(Locale.ROOT, "%.2fM", value / 1000000.0)
    value >= 1000 -> String.format(Locale.ROOT, "%.1fk", value / 1000.0)
    else -> value.toString()
}

@Composable
fun AgentContextInfo(info: JSONObject?) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        if (info?.optBoolean("available") != true) Text("上下文 · 暂无数据", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val tokens = info.optLong("tokens"); val limit = info.optLong("limit")
            val ratio = if (limit > 0) tokens.toDouble() / limit else null
            val estimate = if (info.optBoolean("estimated")) "约 " else ""
            Text("上下文 · $estimate${contextTokens(tokens)}" + if (ratio != null) " / ${contextTokens(limit)} · ${(ratio * 100).toInt()}%" else " · 未返回上限", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (ratio != null) LinearProgressIndicator(progress = { ratio.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(2.dp), drawStopIndicator = {}, color = if (ratio >= .9) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
