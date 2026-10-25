package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chuckiehelper.mobile.startup.StartupService
import com.chuckiehelper.mobile.startup.StartupSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

@Composable
internal fun StartupDialog(close: () -> Unit) {
    val context = LocalContext.current
    val settings = remember { StartupSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var phone by remember { mutableStateOf(settings.phoneTools) }
    var relay by remember { mutableStateOf(settings.phoneRelay) }
    var tunnel by remember { mutableStateOf(settings.portForward) }
    var result by remember { mutableStateOf(settings.result) }
    var restoring by remember { mutableStateOf(StartupService.running) }
    LaunchedEffect(Unit) {
        while (isActive) { result = settings.result; restoring = StartupService.running; delay(1000) }
    }
    AlertDialog(onDismissRequest = close, title = { Text("开机自启") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StartupSwitch("开机自动启动", enabled, true) {
                enabled = it; settings.enabled = it
                if (!it) StartupService.stop(context)
            }
            HorizontalDivider()
            StartupSwitch("启动手机操控", phone, enabled && !restoring) { phone = it; settings.phoneTools = it }
            StartupSwitch("连接手机操控转发通道", relay, enabled && phone && !restoring) { relay = it; settings.phoneRelay = it }
            StartupSwitch("恢复 TCP / UDP 端口转发", tunnel, enabled && !restoring) { tunnel = it; settings.portForward = it }
            if (relay && phone) Text("转发电脑：${runCatching { settings.target("phone")?.name }.getOrNull() ?: "请先连接一台电脑"}", style = MaterialTheme.typography.bodySmall)
            if (tunnel) Text("端口转发电脑：${runCatching { settings.target("tunnel")?.name }.getOrNull() ?: "请先配置端口转发"}", style = MaterialTheme.typography.bodySmall)
            Text("解锁后恢复已保存配置，网络未就绪时自动等待。", style = MaterialTheme.typography.bodySmall)
            Text(result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(enabled = enabled, onClick = {
                    if (restoring) StartupService.stop(context) else StartupService.request(context)
                }) { Text(if (restoring) "停止恢复" else "立即恢复") }
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("系统设置") }
            }
            Text("部分手机需在系统中允许自启动和后台运行。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("完成") } })
}

@Composable
private fun StartupSwitch(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, changed, enabled = enabled, colors = settingSwitchColors())
    }
}
