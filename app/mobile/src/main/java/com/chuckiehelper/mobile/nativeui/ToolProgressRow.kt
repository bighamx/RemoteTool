package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val drivePrefix = Regex("(?<![A-Za-z0-9])([A-Za-z]):([/\\\\])")
internal fun keepDrivePrefixTogether(value: String): String = drivePrefix.replace(value) { match ->
    "${match.groupValues[1]}:\u2060${match.groupValues[2]}\u2060"
}

@Composable
internal fun ToolProgressRow(name: String, description: String, status: String, maxLines: Int = 2) {
    val tool = when (name.lowercase()) { "commandexecution" -> "终端"; "filechange", "apply_patch" -> "文件修改"; else -> name }
    val label = when (status) { "completed", "完成", "成功" -> "成功"; "failed", "失败" -> "失败";
        "running", "执行中", "运行中", "正在运行" -> "正在运行"; "cancelled", "已取消" -> "已取消"; else -> "状态未知" }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(listOf(tool, keepDrivePrefixTogether(description.replace(Regex("\\s+"), " ").trim()))
            .filter { it.isNotBlank() }.joinToString(" · "),
            Modifier.weight(1f).padding(end = 8.dp), style = MaterialTheme.typography.bodySmall, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = when (label) { "失败" -> MaterialTheme.colorScheme.error; "正在运行" -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant })
    }
}
