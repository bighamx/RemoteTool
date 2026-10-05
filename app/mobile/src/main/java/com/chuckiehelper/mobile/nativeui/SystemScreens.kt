package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun SystemScreen(
    api: NativeApi,
    onProcesses: () -> Unit,
    onTerminal: () -> Unit,
    onError: (String) -> Unit,
) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var seconds by remember { mutableIntStateOf(60) }
    var retry by remember { mutableIntStateOf(0) }
    var action by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api, seconds, retry) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    data =
                        api.json("/api/system/performance?seconds=$seconds").optJSONObject("data")
                            ?: throw java.io.IOException("服务没有返回性能数据")
                    error = null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                    error = e.message ?: e.javaClass.simpleName
                }
                delay(3000)
            }
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onProcesses, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Memory, null)
                    Text("进程", Modifier.padding(start = 8.dp))
                }
                FilledTonalButton(onClick = onTerminal, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Terminal, null)
                    Text("终端", Modifier.padding(start = 8.dp))
                }
            }
        }
        item {
            FlowRowCompat {
                listOf(
                        "锁定" to "lock",
                        "睡眠" to "sleep",
                        "休眠" to "hibernate",
                        "取消关机" to "cancel-shutdown",
                        "重启" to "reboot",
                        "关机" to "shutdown",
                    )
                    .forEach { (name, path) ->
                        OutlinedButton(onClick = { action = name to path }) {
                            Text(
                                name,
                                color =
                                    if (path in listOf("shutdown", "reboot"))
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60 to "60 秒", 300 to "5 分钟", 900 to "15 分钟").forEach { (value, label) ->
                    FilterChip(
                        selected = seconds == value,
                        onClick = { seconds = value },
                        label = { Text(label) },
                    )
                }
            }
        }
        if (data == null) item { ErrorPane(error) { retry++ } }
        else {
            val history = data!!.array("history").objects()
            val latest = data!!.optJSONObject("latest")
            val info = data!!.optJSONObject("info") ?: JSONObject()
            if (error != null) item { Text("更新失败：$error", color = MaterialTheme.colorScheme.error) }
            if (latest != null) {
                item {
                    MetricCard(
                        "CPU",
                        "%.0f%%".format(latest.optDouble("cpuPercent")),
                        info.optString("cpuName"),
                        history.map { it.optDouble("cpuPercent").toFloat() },
                        100f,
                        MaterialTheme.colorScheme.primary,
                        headerDetail = cpuTemperatureLabel(latest.array("temperatures").objects()),
                    )
                }
                item {
                    MetricCard(
                        "内存",
                        "%.0f%%".format(latest.optDouble("memoryPercent")),
                        "${bytes(latest.optDouble("usedMemoryMB")*1024*1024)} / ${bytes(latest.optDouble("totalMemoryMB")*1024*1024)}",
                        history.map { it.optDouble("memoryPercent").toFloat() },
                        100f,
                        MaterialTheme.colorScheme.secondary,
                    )
                }
                val networks = latest.array("networks").objects()
                val rx = networks.sumOf { it.optDouble("receiveBytesPerSecond") }
                val tx = networks.sumOf { it.optDouble("sendBytesPerSecond") }
                item {
                    MetricCard(
                        "网络",
                        "↓ ${bytes(rx)}/s",
                        "↑ ${bytes(tx)}/s",
                        history.map { sample ->
                            sample
                                .array("networks")
                                .objects()
                                .sumOf { it.optDouble("receiveBytesPerSecond") }
                                .toFloat()
                        },
                        null,
                        Color(0xffdc8aa6),
                        history.map { sample ->
                            sample
                                .array("networks")
                                .objects()
                                .sumOf { it.optDouble("sendBytesPerSecond") }
                                .toFloat()
                        },
                    )
                }
                val temperatureRows = latest.array("temperatures").objects()
                val coreTemperatures =
                    temperatureRows.filter {
                        it.optString("name").matches(Regex("(?:[PE]-Core|CPU Core) #\\d+"))
                    }
                item("temperatures") {
                    ExpandableSystemCard("硬件温度", "${temperatureRows.size} 项传感器") {
                        Text(
                            latest.optString("sensorStatus"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SensorGrid(temperatureRows - coreTemperatures, false)
                        if (coreTemperatures.isNotEmpty())
                            ExpandableSystemCard("CPU 核心温度", "${coreTemperatures.size} 核") {
                                SensorGrid(coreTemperatures, false)
                            }
                    }
                }
                val fans = latest.array("fans").objects()
                item("fans") {
                    ExpandableSystemCard("风扇与水泵", "${fans.size} 项转速") {
                        if (fans.isEmpty())
                            Text("暂未获取风扇转速", style = MaterialTheme.typography.bodySmall)
                        else SensorGrid(fans, true)
                    }
                }
                item("cores") {
                    val count = latest.array("cpuCores").length()
                    val knownCount =
                        count.takeIf { it > 0 }
                            ?: info.optInt("cpuLogicalProcessors", info.optInt("processorCount"))
                    ExpandableSystemCard(
                        "逻辑处理器",
                        if (knownCount > 0) "$knownCount 核" else "等待处理器数据",
                    ) {
                        if (count == 0)
                            Text("核心曲线暂未获取，稍后自动刷新", style = MaterialTheme.typography.bodySmall)
                        (0 until count).chunked(2).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { index ->
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "CPU ${index+1}",
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                        Sparkline(
                                            history.map {
                                                it.array("cpuCores").optDouble(index, 0.0).toFloat()
                                            },
                                            100f,
                                            MaterialTheme.colorScheme.primary,
                                            Modifier.fillMaxWidth().height(55.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item("device-info") { ExpandableSystemCard("更多设备信息") { SystemDetails(info) } }
        }
    }
    action?.let { (name, path) ->
        ConfirmDialog(name, "对当前电脑执行“$name”？", { action = null }) {
            action = null
            scope.launch {
                try {
                    api.json("/api/system/$path", obj())
                    onError("$name 已发送")
                } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                    onError(e.message ?: "操作失败")
                }
            }
        }
    }
}

@Composable
private fun SensorTile(name: String, value: String, hardware: String, modifier: Modifier) {
    OutlinedCard(modifier.heightIn(min = 86.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
            )
            if (hardware.isNotBlank())
                Text(
                    hardware,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
        }
    }
}

fun sensorLabel(name: String): String =
    when (name) {
        "CPU Package" -> "CPU 封装"
        "Core Max" -> "CPU 核心最高"
        "Core Average" -> "CPU 核心平均"
        "CPU Socket" -> "CPU 脚座"
        "VRM MOS" -> "MOS / 供电"
        "PCH" -> "PCH 芯片组"
        "System" -> "主板系统"
        "CPU Fan" -> "CPU 风扇"
        "Pump Fan" -> "水泵"
        "GPU Core" -> "显卡核心"
        "GPU Hot Spot" -> "显卡热点"
        "Composite Temperature" -> "综合温度"
        "Temperature" -> "温度"
        else ->
            name
                .replace("System Fan #", "系统风扇 #")
                .replace("GPU Fan ", "显卡风扇 ")
                .replace("P-Core #", "性能核 #")
                .replace("E-Core #", "能效核 #")
    }

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun FlowRowCompat(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), content = { content() })
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    values: List<Float>,
    max: Float?,
    color: Color,
    second: List<Float> = emptyList(),
    headerDetail: String? = null,
) {
    ExpandableSystemCard(
        title,
        subtitle,
        defaultExpanded = true,
        value = value,
        valueColor = color,
        headerDetail = headerDetail,
    ) {
        Sparkline(values, max, color, Modifier.fillMaxWidth().height(105.dp), second)
    }
}

@Composable
private fun ExpandableSystemCard(
    title: String,
    summary: String = "",
    defaultExpanded: Boolean = false,
    value: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.primary,
    headerDetail: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(defaultExpanded) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .semantics { stateDescription = if (expanded) "已展开" else "已折叠" }
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                if (summary.isNotBlank())
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
            if (value != null || headerDetail != null)
                Column(horizontalAlignment = Alignment.End) {
                    if (value != null)
                        Text(value, style = MaterialTheme.typography.titleLarge, color = valueColor)
                    if (headerDetail != null)
                        Text(
                            headerDetail,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
        }
        if (expanded)
            Column(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
    }
}

private fun cpuTemperatureLabel(temperatures: List<JSONObject>): String {
    val valid = temperatures.filter { it.optDouble("celsius").isFinite() }
    fun named(name: String) =
        valid.filter { it.optString("name") == name }.maxOfOrNull { it.optDouble("celsius") }
    val core =
        named("Core Max")
            ?: valid
                .filter { it.optString("name").matches(Regex("(?:[PE]-Core|CPU Core) #\\d+")) }
                .maxOfOrNull { it.optDouble("celsius") }
            ?: named("Core Average")
    val pack = named("CPU Package") ?: named("CPU (Tctl/Tdie)") ?: named("CPU Die (average)")
    if (core == null && pack == null) return "温度暂无"
    fun reading(value: Double?) = value?.let { "%.0f".format(it) } ?: "—"
    return "${reading(core)} / ${reading(pack)} °C"
}

@Composable
private fun SensorGrid(rows: List<JSONObject>, fans: Boolean) {
    rows.chunked(2).forEach { pair ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            pair.forEach { sensor ->
                SensorTile(
                    sensorLabel(sensor.optString("name")),
                    if (fans) "${sensor.optDouble("rpm").toInt()} RPM"
                    else "%.0f °C".format(sensor.optDouble("celsius")),
                    sensor.optString("hardware"),
                    Modifier.weight(1f),
                )
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
fun Sparkline(
    values: List<Float>,
    maximum: Float?,
    color: Color,
    modifier: Modifier,
    second: List<Float> = emptyList(),
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        repeat(5) { i ->
            val y = size.height * i / 4
            drawLine(grid.copy(alpha = .55f), Offset(0f, y), Offset(size.width, y), 1f)
        }
        repeat(7) { i ->
            val x = size.width * i / 6
            drawLine(grid.copy(alpha = .35f), Offset(x, 0f), Offset(x, size.height), 1f)
        }
        val high = maximum ?: maxOf(1f, (values + second).maxOrNull() ?: 1f) * 1.15f
        fun path(data: List<Float>): Path =
            Path().apply {
                data.forEachIndexed { i, v ->
                    val x = if (data.size < 2) 0f else i * size.width / (data.size - 1)
                    val y = size.height * (1 - v.coerceIn(0f, high) / high)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
        if (values.isNotEmpty()) {
            val line = path(values)
            val fill =
                path(values).apply {
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
            drawPath(fill, color.copy(alpha = .13f))
            drawPath(line, color, style = Stroke(2.5f))
        }
        if (second.isNotEmpty()) drawPath(path(second), Color(0xff80a9ef), style = Stroke(2f))
    }
}

@Composable
fun ProcessScreen(api: NativeApi, onError: (String) -> Unit) {
    var processes by remember { mutableStateOf<List<JSONObject>?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api, refresh) {
        try {
            processes = api.json("/api/system/processes").array("data").objects()
            error = null
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                query,
                { query = it },
                label = { Text("搜索名称或 PID") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        if (processes == null) ErrorPane(error) { refresh++ }
        else
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    processes!!
                        .filter {
                            (it.optString("name") + it.optInt("id") + it.optString("description"))
                                .contains(query, true)
                        }
                        .sortedByDescending { it.optDouble("memoryMB") },
                    key = { it.optInt("id") },
                ) { p ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row {
                                Text(
                                    p.optString("name"),
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                IconButton(onClick = { selected = p }) {
                                    Icon(
                                        Icons.Outlined.StopCircle,
                                        "结束进程",
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Text(
                                "PID ${p.optInt("id")} · ${p.optDouble("memoryMB").toInt()} MB · CPU ${p.optDouble("cpuPercent")}%",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            p.optString("windowTitle")
                                .takeIf { it.isNotBlank() }
                                ?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
    }
    selected?.let { p ->
        ConfirmDialog(
            "结束进程",
            "结束 ${p.optString("name")}（PID ${p.optInt("id")}）？",
            { selected = null },
        ) {
            selected = null
            scope.launch {
                try {
                    api.json("/api/system/kill/${p.optInt("id")}", obj())
                    refresh++
                } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                    onError(e.message ?: "结束失败")
                }
            }
        }
    }
}
