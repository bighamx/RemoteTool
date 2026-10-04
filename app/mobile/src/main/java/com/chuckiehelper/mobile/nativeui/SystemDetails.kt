package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun SystemDetails(info: JSONObject) {
    if (info.array("gpus").length() == 0) {
        DetailDeviceCard("显卡", Icons.Outlined.Memory) {
            Text("显卡信息暂未获取，稍后自动刷新", style = MaterialTheme.typography.bodySmall)
        }
    }
    info.array("gpus").objects().forEach { gpu ->
        DetailDeviceCard(gpu.optString("name", "显卡"), Icons.Outlined.Memory) {
            val usage = gpu.optDouble("usagePercent", -1.0)
            val total = gpu.optDouble("memoryMB")
            val used = gpu.optDouble("memoryUsedMB", -1.0)
            if (usage >= 0) DetailBar("GPU 占用", "%.0f%%".format(usage), (usage / 100).toFloat())
            if (total > 0 && used >= 0)
                DetailBar(
                    "显存",
                    "${bytes(used * 1024 * 1024)} / ${bytes(total * 1024 * 1024)}",
                    (used / total).toFloat(),
                )
            DetailFields(
                listOf(
                    "驱动版本" to gpu.optString("driverVersion").ifBlank { "未提供" },
                    "GPU 温度" to
                        gpu.optDouble("temperature", -1.0).let {
                            if (it >= 0) "%.0f °C".format(it) else "未提供"
                        },
                )
            )
        }
    }
    info.array("drives").objects().forEach { drive ->
        DetailDeviceCard(
            drive.optString("name", "磁盘") + " · " + drive.optString("driveFormat"),
            Icons.Outlined.Storage,
        ) {
            val total = drive.optDouble("totalGB")
            val used = drive.optDouble("usedGB")
            val free = drive.optDouble("freeGB")
            if (total > 0)
                DetailBar("已用空间", "%.1f / %.1f GB".format(used, total), (used / total).toFloat())
            DetailFields(listOf("可用空间" to "%.1f GB".format(free), "总容量" to "%.1f GB".format(total)))
        }
    }
    info.array("networkAdapters").objects().forEach { network ->
        DetailDeviceCard(network.optString("name", "网络适配器"), Icons.Outlined.Router) {
            val speed = network.optDouble("speedMbps")
            DetailFields(
                listOf(
                    "连接速率" to
                        if (speed >= 1000) "%.1f Gbps".format(speed / 1000)
                        else "%.0f Mbps".format(speed),
                    "MAC 地址" to network.optString("macAddress").ifBlank { "未提供" },
                )
            )
        }
    }
    Text(
        "设备与系统",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
    val labels =
        linkedMapOf(
            "machineName" to "计算机",
            "osVersion" to "操作系统",
            "platform" to "平台",
            "userName" to "当前用户",
            "upTime" to "运行时间",
            "cpuName" to "处理器",
            "cpuCores" to "物理核心",
            "cpuLogicalProcessors" to "逻辑处理器",
            "cpuMaxClockSpeedMHz" to "基准频率",
            "processorCount" to "处理器数量",
            "is64Bit" to "系统架构",
            "totalMemoryMB" to "内存总量",
            "availableMemoryMB" to "可用内存",
            "systemDirectory" to "系统目录",
            "version" to "服务版本",
            "informationalVersion" to "构建版本",
        )
    val fields =
        labels.mapNotNull { (key, label) ->
            val raw = info.opt(key) ?: return@mapNotNull null
            if (raw == JSONObject.NULL || raw.toString().isBlank()) return@mapNotNull null
            val value =
                when (key) {
                    "is64Bit" -> if (info.optBoolean(key)) "64 位" else "32 位"
                    "cpuMaxClockSpeedMHz" ->
                        if (info.optDouble(key) > 0) "%.2f GHz".format(info.optDouble(key) / 1000)
                        else "未提供"
                    "totalMemoryMB",
                    "availableMemoryMB" -> bytes(info.optDouble(key) * 1024 * 1024)
                    else -> raw.toString()
                }
            label to value
        }
    DetailFields(fields)
}

@Composable
private fun DetailDeviceCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    icon,
                    null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
            }
            content()
        }
    }
}

@Composable
private fun DetailBar(label: String, value: String, fraction: Float) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.labelMedium)
        }
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            drawStopIndicator = {},
            modifier = Modifier.fillMaxWidth().height(5.dp),
        )
    }
}

@Composable
private fun DetailFields(fields: List<Pair<String, String>>) {
    fields.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            pair.forEach { (name, value) ->
                Column(
                    Modifier.weight(1f).padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}
