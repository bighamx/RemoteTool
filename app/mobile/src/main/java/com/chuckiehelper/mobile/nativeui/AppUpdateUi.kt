package com.chuckiehelper.mobile.nativeui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.chuckiehelper.mobile.BuildConfig
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun Context.updateActivity(): Activity? = generateSequence(this) { (it as? ContextWrapper)?.baseContext }
    .filterIsInstance<Activity>().firstOrNull()

@Composable
internal fun AppUpdateHost(model: AppUpdateModel) {
    val activity = LocalContext.current.updateActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(model, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(1800)
            model.onForeground(activity)
            awaitCancellation()
        }
    }
    DisposableEffect(model, lifecycle, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) model.onForeground(activity)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!model.panelVisible) return
    val release = model.release
    AlertDialog(
        onDismissRequest = { model.panelVisible = false },
        icon = { Icon(Icons.Outlined.SystemUpdate, null) },
        title = { Text(when (model.phase) {
            UpdatePhase.Downloading -> "正在下载更新"
            UpdatePhase.Ready -> "更新已准备就绪"
            UpdatePhase.Available -> "发现新版本"
            else -> "应用更新"
        }) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 440.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前 v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (release != null) {
                    Text("v${release.versionName} · ${updateBytes(release.size)}", style = MaterialTheme.typography.titleMedium)
                    if (release.notes.isNotBlank()) Text(release.notes, style = MaterialTheme.typography.bodyMedium)
                } else Text(model.message, style = MaterialTheme.typography.bodyMedium)
                when (model.phase) {
                    UpdatePhase.Checking -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    UpdatePhase.Downloading -> {
                        LinearProgressIndicator(progress = { (model.downloaded.toFloat() / (release?.size ?: 1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        Text("${updateBytes(model.downloaded)} / ${updateBytes(release?.size ?: 0)}", style = MaterialTheme.typography.labelMedium)
                        Text("可关闭此窗口，下载会继续。", style = MaterialTheme.typography.bodySmall)
                    }
                    UpdatePhase.Ready -> Text("文件、版本和签名已校验，通过系统安装器覆盖安装。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary)
                    else -> Unit
                }
                model.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                HorizontalDivider()
                UpdateToggle("自动检查更新", "启动和回到前台检查，间隔至少 6 小时", model.automatic, model::updateAutomatic)
                UpdateToggle("仅 Wi-Fi 自动下载", "移动网络下可以手动下载", model.wifiOnly, model::updateWifiOnly)
                if (model.checkedAt > 0) Text("上次检查：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(model.checkedAt))}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            when (model.phase) {
                UpdatePhase.Ready -> TextButton(enabled = !model.busy && activity != null, onClick = { activity?.let(model::install) }) { Text("安装更新") }
                UpdatePhase.Available -> TextButton(enabled = !model.busy, onClick = { model.download() }) { Text(if (model.error == null) "下载更新" else "重试下载") }
                UpdatePhase.Downloading -> TextButton(onClick = { model.cancelDownload() }) { Text("取消下载") }
                else -> TextButton(enabled = !model.busy, onClick = { model.check(manual = true) }) { Text("检查更新") }
            }
        },
        dismissButton = { TextButton(onClick = { model.later() }) { Text(if (release == null) "关闭" else "稍后") } },
    )
}

@Composable
private fun UpdateToggle(title: String, description: String, checked: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = change)
    }
}

private fun updateBytes(size: Long) = String.format(Locale.US, "%.1f MB", size / 1048576.0)
