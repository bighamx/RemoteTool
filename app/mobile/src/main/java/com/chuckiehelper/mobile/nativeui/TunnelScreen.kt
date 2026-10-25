package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import android.provider.Settings
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.chuckiehelper.mobile.tunnel.*

@Composable
internal fun TunnelScreen(api: NativeApi, deviceId: String, deviceName: String) {
    val context = LocalContext.current
    val settings = remember { TunnelSettings(context) }
    var error by remember { mutableStateOf<String?>(null) }
    var rules by remember(deviceId) { mutableStateOf(runCatching { settings.rules(deviceId) }.getOrElse { emptyList() }) }
    val status by TunnelRuntime.status.collectAsState()
    val owns = status.deviceId == deviceId
    val editable = !status.running || !owns
    var editing by remember { mutableStateOf<TunnelRule?>(null) }
    var deleting by remember { mutableStateOf<TunnelRule?>(null) }
    fun save(next: List<TunnelRule>) {
        try { settings.save(deviceId, next); rules = next; error = null }
        catch (failure: Exception) { error = failure.message }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text(if (status.running && !owns) "正在为 ${status.name} 转发" else if (owns) status.state else "未开启",
                style = MaterialTheme.typography.titleMedium)
            if (owns) Text("连接 ${status.active} · 上传 ${Formatter.formatShortFileSize(context, status.sent)} · 下载 ${Formatter.formatShortFileSize(context, status.received)} · 丢弃 ${status.dropped}",
                style = MaterialTheme.typography.bodySmall)
            (error ?: status.error.takeIf { owns })?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !status.running && rules.isNotEmpty(), onClick = {
                    try { TunnelRuntime.start(context, deviceId, deviceName, api, rules); error = null }
                    catch (failure: Exception) { error = failure.message }
                }) { Text("开启转发") }
                OutlinedButton(enabled = status.running, onClick = { TunnelRuntime.stop(context) }) { Text("停止转发") }
                TextButton(enabled = editable && rules.size < 32, onClick = {
                    val id = (1..32).first { candidate -> rules.none { it.id == candidate } }
                    editing = TunnelRule(id, "", "tcp", "phone_to_pc", "127.0.0.1", 18080, "127.0.0.1", 8080)
                }) { Text("添加") }
            }
            Text("目标可填写对端局域网 IP 或主机名；不占用 VPN。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) { Text("后台电池设置") }
        }
        items(rules, key = { it.id }) { rule ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(rule.name.ifBlank { "规则 ${rule.id}" }, style = MaterialTheme.typography.titleSmall)
                    Text("${rule.protocol.uppercase()} · ${if (rule.phoneListens) "手机 → 电脑网络" else "电脑 → 手机网络"}", style = MaterialTheme.typography.labelMedium)
                    Text("监听 ${rule.listenHost}:${rule.listenPort}\n目标 ${rule.targetHost}:${rule.targetPort}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(enabled = editable, onClick = { editing = rule }) { Text("编辑") }
                        TextButton(enabled = editable, onClick = { deleting = rule }) { Text("删除") }
                    }
                }
            }
        }
    }
    editing?.let { rule -> TunnelRuleDialog(rule, onClose = { editing = null }, onSave = { updated ->
        val next = if (rules.any { it.id == updated.id }) rules.map { if (it.id == updated.id) updated else it } else rules + updated
        save(next); editing = null
    }) }
    deleting?.let { rule -> AlertDialog(onDismissRequest = { deleting = null }, text = { Text("删除这条转发规则？") },
        confirmButton = { TextButton(onClick = { save(rules.filter { it.id != rule.id }); deleting = null }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } }) }
}

@Composable
private fun TunnelRuleDialog(rule: TunnelRule, onClose: () -> Unit, onSave: (TunnelRule) -> Unit) {
    var name by remember { mutableStateOf(rule.name) }
    var udp by remember { mutableStateOf(rule.udp) }
    var phoneListens by remember { mutableStateOf(rule.phoneListens) }
    var listen by remember { mutableStateOf(rule.listenPort.toString()) }
    var host by remember { mutableStateOf(rule.targetHost) }
    var port by remember { mutableStateOf(rule.targetPort.toString()) }
    var localNetwork by remember { mutableStateOf(rule.listenHost == "0.0.0.0" || rule.listenHost == "::") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onClose, title = { Text("转发规则") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!phoneListens, { phoneListens = false }, label = { Text("电脑 → 手机") })
                FilterChip(phoneListens, { phoneListens = true }, label = { Text("手机 → 电脑") })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(!udp, { udp = false }, label = { Text("TCP") })
                FilterChip(udp, { udp = true }, label = { Text("UDP") })
            }
            OutlinedTextField(name, { name = it }, label = { Text("名称（可选）") }, singleLine = true)
            OutlinedTextField(listen, { listen = it }, label = { Text(if (phoneListens) "手机监听端口" else "电脑监听端口") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(localNetwork, { localNetwork = it }); Text("允许其他设备访问（所有网卡）", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(host, { host = it }, label = { Text("目标 IP / 主机名") }, singleLine = true)
            OutlinedTextField(port, { port = it }, label = { Text("目标端口") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Text(if (phoneListens) "目标地址由电脑访问，可填电脑内网地址。" else "目标地址由手机访问，可填手机 Wi-Fi 内网地址。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        try {
            val updated = TunnelRule(rule.id, name.trim(), if (udp) "udp" else "tcp", if (phoneListens) "phone_to_pc" else "pc_to_phone",
                if (localNetwork) "0.0.0.0" else "127.0.0.1", listen.toIntOrNull() ?: 0, host.trim(), port.toIntOrNull() ?: 0)
            updated.validate(); onSave(updated)
        } catch (failure: Exception) { error = failure.message }
    }) { Text("保存") } }, dismissButton = { TextButton(onClick = onClose) { Text("取消") } })
}
