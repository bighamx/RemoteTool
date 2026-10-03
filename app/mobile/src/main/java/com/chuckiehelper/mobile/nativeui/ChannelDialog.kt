package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ChannelDialog(model: NativeModel, device: Device) {
    var selected by remember(device.id) { mutableStateOf(model.preferredChannel(device)) }
    val current = model.session?.takeIf { it.device.id == device.id }?.api?.base
    val checks = model.channelChecks
    val enabled = !model.connecting && !model.checkingChannels
    val available = checks.any { it.url == selected && it.reachable }
    AlertDialog(
        onDismissRequest = { model.closeChannels() },
        title = { Text("连接通道 · ${device.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("显示所有已保存地址。响应时间为设备标识接口实测，选择后再连接。", style = MaterialTheme.typography.bodySmall)
                if (model.checkingChannels || model.connecting)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 380.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(checks, key = { it.url }) { check ->
                        val canSelect = check.reachable && enabled
                        val chosen = selected == check.url
                        Surface(
                            modifier =
                                Modifier.fillMaxWidth().clickable(enabled = canSelect) {
                                    selected = check.url
                                },
                            shape = MaterialTheme.shapes.medium,
                            color =
                                if (chosen) MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                        ) {
                            Row(
                                Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = chosen,
                                    onClick = { selected = check.url },
                                    enabled = canSelect,
                                )
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        check.url,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        when {
                                            check.checking -> "检测中…"
                                            check.reachable ->
                                                "${check.ms} ms · 可用" +
                                                    if (current == check.url) " · 当前通道" else ""
                                            else -> check.error ?: "不可达"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color =
                                            if (check.error != null) MaterialTheme.colorScheme.error
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { model.refreshChannels() },
                    enabled = !model.connecting && !model.checkingChannels,
                ) {
                    Text("重新检测")
                }
                if (model.connecting) Text("正在连接所选地址…", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = { selected?.let { model.selectChannel(it) } },
                enabled = enabled && available,
            ) {
                Text(if (current == null) "连接" else if (selected == current) "使用当前通道" else "切换通道")
            }
        },
        dismissButton = {
            TextButton(onClick = { model.closeChannels() }) {
                Text("取消")
            }
        },
    )
}
