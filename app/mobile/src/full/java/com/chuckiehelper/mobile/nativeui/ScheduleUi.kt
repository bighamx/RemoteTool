package com.chuckiehelper.mobile.nativeui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun scheduleLabel(seconds: Long): String =
    SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(seconds * 1000))

internal fun scheduleStatus(row: JSONObject): String = when (row.optString("status")) {
    "pending" -> if (row.optString("last_error").isNotBlank()) "等待发送" else "待发送"
    "sending" -> "发送中"
    "sent" -> "已提交"
    "failed" -> "发送失败"
    "cancelled" -> "已取消"
    "delivery_unknown" -> "结果待确认"
    else -> "状态未知"
}

@Composable
internal fun ScheduleStrip(schedules: List<JSONObject>, busy: Boolean, error: String?, onOpen: () -> Unit, onCancel: (String) -> Unit) {
    val pending = schedules.filter { it.optString("status") in setOf("pending", "sending") }
    val failures = schedules.count { it.optString("status") in setOf("failed", "delivery_unknown") }
    if (pending.isEmpty() && failures == 0 && error == null) return
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (failures > 0 || error != null) item {
            AssistChip(onClick = onOpen,
                label = { Text(if (error != null) "定时记录读取失败" else "$failures 条定时消息需要查看") },
                colors = AssistChipDefaults.assistChipColors(labelColor = MaterialTheme.colorScheme.error))
        }
        items(pending, key = { it.optString("id") }) { row ->
            InputChip(
                selected = false,
                onClick = onOpen,
                label = {
                    Text(
                        scheduleStatus(row) + " · " + scheduleLabel(row.optLong("send_at")) + " · " +
                            row.optString("text").replace('\n', ' ').take(18),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    if (row.optString("status") == "pending") IconButton(onClick = { onCancel(row.optString("id")) }, enabled = !busy, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, "取消定时消息", modifier = Modifier.size(16.dp))
                    }
                },
            )
        }
    }
}

@Composable
internal fun ScheduleHistoryDialog(model: HermesModel, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(model.selectedId) { model.loadSchedules() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("定时消息记录") },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                model.schedulesReadError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
                if (model.schedules.isEmpty() && !model.schedulesLoading && model.schedulesReadError == null)
                    item { Text("暂无定时消息记录") }
                items(model.schedules, key = { it.optString("id") }) { row ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            val failed = row.optString("status") in setOf("failed", "delivery_unknown")
                            Text(scheduleStatus(row), style = MaterialTheme.typography.titleSmall,
                                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            Text(row.optString("text"))
                            val planned = row.optLong("scheduled_at", row.optLong("send_at"))
                            Text("计划发送 " + scheduleLabel(planned), style = MaterialTheme.typography.bodySmall)
                            if (row.optString("status") == "pending" && row.optLong("send_at") != planned)
                                Text("下次检查 " + scheduleLabel(row.optLong("send_at")), style = MaterialTheme.typography.bodySmall)
                            if (row.optLong("sent_at") > 0)
                                Text("已提交 " + scheduleLabel(row.optLong("sent_at")), style = MaterialTheme.typography.bodySmall)
                            if (row.optLong("completed_at") > 0 && row.optString("status") != "sent")
                                Text("状态更新 " + scheduleLabel(row.optLong("completed_at")), style = MaterialTheme.typography.bodySmall)
                            if (row.optString("last_error").isNotBlank())
                                Text(row.optString("last_error"), color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            if (row.optString("status") == "pending")
                                TextButton(onClick = { scope.launch { model.cancelSchedule(row.optString("id")) } }, enabled = !model.scheduleBusy) { Text("取消发送") }
                        }
                    }
                }
                model.scheduleError?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { scope.launch { model.loadSchedules() } }, enabled = !model.schedulesLoading) { Text(if (model.schedulesLoading) "读取中…" else "刷新") } },
    )
}

/** 定时发送对话框：内容 + 时间（快捷项或自定义日期时间）。 */
@Composable
internal fun ScheduleComposerDialog(model: HermesModel, initialText: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    val calendar = remember { Calendar.getInstance().apply { add(Calendar.MINUTE, 60); set(Calendar.SECOND, 0) } }
    var at by remember { mutableStateOf(calendar.timeInMillis) }
    var localError by remember { mutableStateOf<String?>(null) }

    fun pickDate() {
        val current = Calendar.getInstance().apply { timeInMillis = at }
        DatePickerDialog(context, { _, year, month, day ->
            current.set(year, month, day)
            at = current.timeInMillis
        }, current.get(Calendar.YEAR), current.get(Calendar.MONTH), current.get(Calendar.DAY_OF_MONTH)).show()
    }
    fun pickTime() {
        val current = Calendar.getInstance().apply { timeInMillis = at }
        TimePickerDialog(context, { _, hour, minute ->
            current.set(Calendar.HOUR_OF_DAY, hour)
            current.set(Calendar.MINUTE, minute)
            at = current.timeInMillis
        }, current.get(Calendar.HOUR_OF_DAY), current.get(Calendar.MINUTE), true).show()
    }
    fun quick(hour: Int, minute: Int, plusDays: Int) {
        val target = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_MONTH, plusDays)
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0)
        }
        at = target.timeInMillis
    }

    AlertDialog(
        onDismissRequest = { if (!model.scheduleBusy) onDismiss() },
        title = { Text("定时发送") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 6,
                    enabled = !model.scheduleBusy,
                    label = { Text("消息内容") },
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { at = System.currentTimeMillis() + 60 * 60 * 1000 }, label = { Text("1 小时后") })
                    AssistChip(onClick = { quick(9, 0, 1) }, label = { Text("明天 9:00") })
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { pickDate() }, label = { Text("日期…") })
                    AssistChip(onClick = { pickTime() }, label = { Text("时间…") })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(scheduleLabel(at / 1000), style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "提交后由服务端到点发送，手机退出也不影响；会话正在执行时会排队等当前任务结束。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                (localError ?: model.scheduleError)?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !model.scheduleBusy && text.isNotBlank(),
                onClick = {
                    if (at <= System.currentTimeMillis()) { localError = "请选择将来的时间"; return@TextButton }
                    if (at > System.currentTimeMillis() + 90L * 24 * 3600 * 1000) { localError = "最多提前 90 天"; return@TextButton }
                    localError = null
                    scope.launch { if (model.addSchedule(text, at / 1000)) onDismiss() }
                },
            ) { Text(if (model.scheduleBusy) "提交中…" else "确定") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !model.scheduleBusy) { Text("取消") } },
    )
}
