package com.chuckiehelper.mobile.nativeui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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

/** 会话输入区上方的待发定时消息；点 ✕ 取消。 */
@Composable
internal fun ScheduleStrip(schedules: List<JSONObject>, busy: Boolean, onCancel: (String) -> Unit) {
    if (schedules.isEmpty()) return
    LazyRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(schedules, key = { it.optString("id") }) { row ->
            InputChip(
                selected = false,
                onClick = { },
                label = {
                    Text(
                        "⏰ " + scheduleLabel(row.optLong("send_at")) + " · " +
                            row.optString("text").replace('\n', ' ').take(18),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                trailingIcon = {
                    IconButton(onClick = { onCancel(row.optString("id")) }, enabled = !busy, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Outlined.Close, "取消定时消息", modifier = Modifier.size(16.dp))
                    }
                },
            )
        }
    }
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
