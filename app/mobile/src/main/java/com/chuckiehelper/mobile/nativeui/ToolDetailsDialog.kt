package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
internal fun ToolDetailsDialog(api: NativeApi, agent: String, session: String, summary: String, onDismiss: () -> Unit) {
    var rows by remember(session, summary) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var limit by remember(session, summary) { mutableIntStateOf(50) }
    var total by remember { mutableIntStateOf(0) }
    var changed by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var failure by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, session, summary, limit, retry) {
        loading = true
        while (true) {
            try {
                val collected = mutableListOf<JSONObject>()
                var offset = 0
                var running = false
                var version: String? = null
                var changedDuringRead = false
                do {
                    val response = api.json("/api/$agent/sessions/$session/tools/$summary?offset=$offset")
                    total = response.optInt("total"); changed = response.optInt("file_changes")
                    running = response.optBoolean("running")
                    val currentVersion = response.optString("version")
                    if (version != null && version != currentVersion) changedDuringRead = true
                    version = currentVersion
                    val page = response.array("data").objects()
                    collected += page; offset += page.size
                } while (offset < minOf(total, limit) && page.isNotEmpty())
                rows = collected.distinctBy { it.optString("id") }
                failure = null; loading = false
                if (!running && !changedDuringRead) break
                delay(2000)
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { failure = error.message ?: "无法读取工具详情"; loading = false; break }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("工具 ${total - changed} 次 · 文件修改 $changed 次") },
        text = {
            Column {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 8.dp))
                failure?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { retry++ }) { Text("重试") } }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(rows, key = { it.optString("id") }) { tool ->
                        Column {
                            val name = if (tool.optString("category") == "file_change") "文件修改" else tool.optString("tool", "工具")
                            val status = when (tool.optString("status")) { "completed" -> "成功"; "failed" -> "失败"; "running" -> "正在运行"; "cancelled" -> "已取消"; else -> "状态未知" }
                            Row { Text(name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(status, style = MaterialTheme.typography.labelMedium,
                                color = if (status == "失败") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) }
                            tool.optString("description").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    if (rows.size < total) item { TextButton(onClick = { limit += 50 }) { Text("加载更多（${rows.size}/$total）") } }
                    if (!loading && failure == null && rows.isEmpty()) item { Text("暂无工具调用") }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}
