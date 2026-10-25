package com.chuckiehelper.mobile.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import org.json.JSONObject

@Composable
internal fun SessionDetailsDialog(api: NativeApi, agent: String, listed: JSONObject, onDismiss: () -> Unit) {
    val id = listed.optString("id")
    val context = LocalContext.current
    var refresh by remember(id, api) { mutableIntStateOf(0) }
    var detail by remember(id, api) { mutableStateOf<JSONObject?>(null) }
    var contextInfo by remember(id, api) { mutableStateOf<JSONObject?>(null) }
    var usageInfo by remember(id, api) { mutableStateOf<JSONObject?>(null) }
    var activity by remember(id, api) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(id, api) { mutableStateOf(true) }
    var failedParts by remember(id, api) { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(api, agent, id, refresh) {
        loading = true
        failedParts = emptyList()
        suspend fun read(path: String): JSONObject? = try {
            api.json(path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val path = "/api/$agent/sessions/${q(id)}"
        val detailRequest = async { read(path) }
        val contextRequest = async { read("$path/context") }
        val activityRequest = async { read("$path/activity") }
        val response = detailRequest.await()
        detail = response?.optJSONObject("session") ?: response?.takeIf { it.has("id") }
        val contextResponse = contextRequest.await()
        contextInfo = if (agent == "codex") contextResponse?.optJSONObject("context") else contextResponse
        usageInfo = if (agent == "codex") contextResponse?.optJSONObject("usage")
            ?: detail?.optJSONObject("usage") else null
        activity = activityRequest.await()
        failedParts = buildList {
            if (detail == null) add("会话信息")
            if (contextResponse == null) add("上下文")
            if (activity == null) add("活动状态")
        }
        loading = false
    }

    fun field(name: String): String? = sessionDetailValue(detail, name) ?: sessionDetailValue(listed, name)
    fun copyId() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("会话 ID", id))
        Toast.makeText(context, "已复制会话 ID", Toast.LENGTH_SHORT).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("会话详情") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (failedParts.isNotEmpty()) {
                    Text("${failedParts.joinToString("、")}暂时无法读取，已显示可用信息。",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { refresh++ }) { Text("重试") }
                }
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        DetailSection("基本信息")
                        DetailField("标题", field("title"))
                        DetailField("会话 ID", id)
                        if (agent == "codex") {
                            DetailField("source", field("source") ?: "null")
                            DetailField("originator", field("originator") ?: "null")
                        } else {
                            DetailField("来源", field("source"))
                        }
                        DetailField("最后活跃", sessionDetailTime(field("last_active")))
                        if (agent == "codex") {
                            DetailField("工作目录", field("cwd"), protectDrivePrefix = true)
                            DetailField("项目 ID", field("project_id"))
                            DetailField("模型", field("model"))
                            DetailField("提供方", field("provider"))
                            DetailField("推理强度", field("reasoning_effort"))
                            DetailField("服务等级", field("service_tier"))
                            DetailField("置顶", sessionDetailFlag(detail ?: listed, "pinned"))
                            val status = detail?.optJSONObject("status") ?: listed.optJSONObject("status")
                            DetailField("原生状态", status?.let { sessionDetailValue(it, "type") })
                            val control = detail?.optJSONObject("control")
                            DetailField("写入状态", when (control?.optString("state")) {
                                "mobile" -> "手机端持有"
                                "busy" -> "其他客户端正在运行"
                                "writer_held" -> "其他客户端持有写入权"
                                "available" -> "可写入"
                                else -> null
                            })
                            DetailField("写入说明", control?.let { sessionDetailValue(it, "message") })
                        } else {
                            DetailField("模型", field("model"))
                            DetailField("创建时间", sessionDetailTime(field("started_at")))
                            DetailField("结束时间", sessionDetailTime(field("ended_at")))
                            DetailField("结束原因", field("end_reason"))
                            DetailField("父会话 ID", field("parent_session_id"))
                            DetailField("消息数", sessionDetailCount(field("message_count")))
                            DetailField("工具调用数", sessionDetailCount(field("tool_call_count")))
                            DetailField("API 调用数", sessionDetailCount(field("api_call_count")))
                            DetailField("置顶", sessionDetailFlag(detail ?: listed, "pinned"))
                            DetailField("归档", sessionDetailFlag(detail ?: listed, "archived"))
                        }
                        if (activity?.has("available") == true && activity?.optBoolean("available") == true) {
                            DetailField("任务", if (activity?.optBoolean("running") == true) "运行中" else "空闲")
                            DetailField("任务开始", sessionDetailTime(activity?.let { sessionDetailValue(it, "started_at") }))
                        } else if (agent == "hermes") {
                            DetailField("任务", field("status"))
                        }
                        DetailSection("上下文")
                        contextInfo?.takeIf { it.optBoolean("available") }?.let { info ->
                            DetailField("已用", sessionDetailCompactTokens(sessionDetailValue(info, "tokens")))
                            DetailField("上限", sessionDetailCompactTokens(sessionDetailValue(info, "limit")))
                            DetailField("用量性质", if (info.optBoolean("estimated")) "估算" else "已记录")
                        } ?: Text("暂无可用数据", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (agent == "codex") {
                            DetailSection("累计 Token 用量")
                            usageInfo?.takeIf { it.has("input_tokens") || it.has("output_tokens") }?.let { usage ->
                                val input = sessionDetailValue(usage, "input_tokens")
                                val hit = sessionDetailValue(usage, "cached_input_tokens")
                                DetailField("输入Token", sessionDetailCompactTokens(input))
                                DetailField("- 命中", sessionDetailCompactTokens(hit))
                                DetailField("- 未命中", sessionDetailCompactTokens(sessionDetailTokenDifference(input, hit)))
                                DetailField("输出Token", sessionDetailCompactTokens(sessionDetailValue(usage, "output_tokens")))
                            } ?: Text("当前服务端未返回累计用量", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (agent == "hermes") {
                            DetailSection("Token 用量与费用")
                            val hit = field("cache_read_tokens")
                            val miss = sessionDetailTokenSum(field("input_tokens"), field("cache_write_tokens"))
                            DetailField("输入Token", sessionDetailCompactTokens(sessionDetailTokenSum(hit, miss)))
                            DetailField("- 命中", sessionDetailCompactTokens(hit))
                            DetailField("- 未命中", sessionDetailCompactTokens(miss))
                            DetailField("输出Token", sessionDetailCompactTokens(field("output_tokens")))
                            DetailField("预估费用", sessionDetailCost(field("estimated_cost_usd")))
                            DetailField("实际费用", sessionDetailCost(field("actual_cost_usd")))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = ::copyId) { Text("复制 ID") } },
    )
}

@Composable
private fun DetailSection(title: String) {
    HorizontalDivider(Modifier.padding(top = 4.dp))
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun DetailField(label: String, value: String?, protectDrivePrefix: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(0.34f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (protectDrivePrefix) {
            val context = LocalContext.current
            val shown = if (value.length > 2 && value[1] == ':' && value[2] in "\\/")
                value.substring(0, 2) + "\u2060" + value.substring(2) else value
            Box(Modifier.weight(0.66f)) {
                DisableSelection {
                    Text(shown, Modifier.fillMaxWidth().clickable {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("工作目录", value))
                        Toast.makeText(context, "已复制工作目录", Toast.LENGTH_SHORT).show()
                    }, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            Text(value, Modifier.weight(0.66f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun sessionDetailValue(json: JSONObject?, key: String): String? =
    json?.takeIf { it.has(key) && !it.isNull(key) }?.optString(key)?.takeIf { it.isNotBlank() && it != "null" }

private fun sessionDetailFlag(json: JSONObject?, key: String): String? =
    json?.takeIf { it.has(key) && !it.isNull(key) }?.let { if (it.optBoolean(key)) "是" else "否" }

private fun sessionDetailCount(value: String?): String? = value?.toLongOrNull()?.let {
    java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(it)
} ?: value

private fun sessionDetailTokenSum(first: String?, second: String?): String? {
    val a = first?.toLongOrNull() ?: return null
    val b = second?.toLongOrNull() ?: return null
    return runCatching { Math.addExact(a, b).toString() }.getOrNull()
}

private fun sessionDetailTokenDifference(total: String?, part: String?): String? {
    val a = total?.toLongOrNull() ?: return null
    val b = part?.toLongOrNull() ?: return null
    return runCatching { Math.subtractExact(a, b).takeIf { it >= 0 }?.toString() }.getOrNull()
}

private fun sessionDetailCompactTokens(value: String?): String? = value?.toLongOrNull()?.let(::compactTokenCount) ?: value

private fun sessionDetailCost(value: String?): String? = value?.toDoubleOrNull()?.let {
    String.format(Locale.US, "$%.4f", it)
}

private fun sessionDetailTime(value: String?): String? =
    parseMessageTimestamp(value)?.let { timestamp ->
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
