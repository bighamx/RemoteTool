@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.chuckiehelper.mobile.phonecontrol.PhoneEngine
import kotlinx.coroutines.*
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun logTime(value: Long) = if (value <= 0) "—" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(value))
private fun connectionReason(reason: String) = when (reason) {
    "user_disconnected" -> "用户主动断开"
    "agent_disconnected" -> "Agent 已结束连接"
    "relay_disconnected" -> "转发通道断开"
    "service_stopped" -> "操控服务已停止"
    "process_restarted" -> "App 进程已重启"
    "idle_expired" -> "连接长时间无活动"
    else -> reason.ifBlank { "已结束" }
}
private fun callState(state: String) = when (state) {
    "running" -> "执行中"
    "queued" -> "排队中"
    "completed" -> "成功"
    "delivery_unknown" -> "结果待核对"
    "connection_closed_by_user" -> "用户已断开"
    else -> "失败 · $state"
}

@Composable
internal fun PhoneConnectionsScreen(close: () -> Unit) {
    val context = LocalContext.current
    val engine = remember { PhoneEngine.get(context) }
    val scope = rememberCoroutineScope()
    var history by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var selectedCall by remember { mutableStateOf<String?>(null) }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var connection by remember { mutableStateOf<JSONObject?>(null) }
    var call by remember { mutableStateOf<JSONObject?>(null) }
    var limit by remember { mutableIntStateOf(100) }
    var refresh by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var disconnect by remember { mutableStateOf<JSONObject?>(null) }
    var clear by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var connectionInfoExpanded by remember(selected) { mutableStateOf(false) }
    val currentListState = rememberLazyListState()
    val historyListState = rememberLazyListState()
    val timelineState = rememberSaveable(selected, saver = LazyListState.Saver) { LazyListState() }
    val callDetailState = rememberSaveable(selectedCall, saver = LazyListState.Saver) { LazyListState() }
    var returnedCall by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(returnedCall, selectedCall) {
        if (returnedCall != null && selectedCall == null) { delay(500); returnedCall = null }
    }
    fun back() { when { selectedCall != null -> { returnedCall = selectedCall; selectedCall = null }; selected != null -> { selected = null; limit = 100; returnedCall = null }; else -> close() } }
    BackHandler { back() }
    LaunchedEffect(history, selected, selectedCall, limit, refresh) {
        do {
            try {
                val result = withContext(Dispatchers.IO) {
                    val store = engine.connectionLog
                    if (selectedCall != null) Triple(store.call(selectedCall!!), emptyList(), null)
                    else if (selected != null) Triple(null, (0 until limit step 100).flatMap { store.calls(selected!!, it) }, store.connection(selected!!))
                    else Triple(null, (0 until limit step 100).flatMap { store.connections(history, it) }, null)
                }
                call = result.first
                // Keep the timeline and its keyed items mounted in state while
                // showing a call, so returning cannot clamp its saved scroll anchor.
                if (selectedCall == null) { rows = result.second; connection = result.third }
                error = null
            } catch (_: Exception) { error = "读取本地连接记录失败，请重试" }
            finally { loading = false }
            delay(1500)
        } while (isActive && selectedCall == null)
    }
    val states = engine.connectionStates().associateBy { it.id }
    Scaffold(topBar = { TopAppBar(
        title = { Text(if (selectedCall != null) "调用详情" else if (selected != null) "连接详情" else "连接与日志") },
        navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
        actions = { if (selected == null) IconButton(onClick = { clear = true }) { Icon(Icons.Outlined.DeleteOutline, "清空日志") } }
    ) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            state = if (selectedCall != null) callDetailState else if (selected != null) timelineState else if (history) historyListState else currentListState,
            verticalArrangement = Arrangement.spacedBy(if (selected != null && selectedCall == null) 0.dp else 12.dp)) {
            if (selected == null) {
                item {
                    TabRow(selectedTabIndex = if (history) 1 else 0) {
                        Tab(!history, { history = false; limit = 100 }, text = { Text("当前连接") })
                        Tab(history, { history = true; limit = 100 }, text = { Text("历史连接") })
                    }
                }
                item { Text("记录仅保存在本机 · 保留30天，最多2万条调用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(rows, key = { it.getString("id") }) { row ->
                    val state = states[row.optString("owner")]
                    OutlinedCard(onClick = { selected = row.getString("id"); limit = 100; loading = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (row.optString("source") == "直连手机") Icons.Outlined.PhoneAndroid else Icons.Outlined.CloudQueue, null, tint = MaterialTheme.colorScheme.primary)
                                Text(row.optString("name"), Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
                                if (!history && state?.ownsUi == true) Text("占用界面", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                Icon(Icons.Outlined.ChevronRight, null)
                            }
                            Text("${row.optString("source")} · ${row.optLong("call_count")} 次调用", style = MaterialTheme.typography.bodySmall)
                            Text(if (history) connectionReason(row.optString("reason")) else state?.operation?.ifBlank { "已连接" } ?: "已连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${logTime(row.optLong("started"))} 连接 · ${logTime(row.optLong("last_seen"))} 活动", style = MaterialTheme.typography.bodySmall)
                            if (!history) TextButton(onClick = { disconnect = row }) { Icon(Icons.Outlined.LinkOff, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("断开连接") }
                        }
                    }
                }
            } else if (selectedCall != null) {
                call?.let { row ->
                    item { LogPanel(row.optString("tool")) {
                        Text(callState(row.optString("state")), color = if (row.optString("state") == "completed") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        Text("开始：${logTime(row.optLong("started"))}")
                        if (!row.isNull("ended")) Text("耗时：${row.optLong("ended") - row.optLong("started")} ms")
                        SelectionContainer { Text("请求 ID：${row.optString("request_id")}", style = MaterialTheme.typography.bodySmall) }
                    } }
                    item { JsonLogPanel("调用参数", row.optString("arguments")) }
                    item { JsonLogPanel("执行结果", row.optString("result").takeUnless { it == "null" }.orEmpty().ifBlank { "等待执行回执" }) }
                    item { Text("敏感字段已隐藏；图片、文件二进制内容不记录；长内容可能截断。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            } else {
                connection?.let { row ->
                    val state = states[row.optString("owner")]?.takeIf { row.isNull("ended") }
                    item { LogPanel(row.optString("name")) {
                        val status = if (row.isNull("ended")) if (state?.ownsUi == true) "占用界面" else "已连接" else connectionReason(row.optString("reason"))
                        Text("${row.optString("source")} · $status", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                        Text("${logTime(row.optLong("started"))} 连接", style = MaterialTheme.typography.bodySmall)
                        state?.let { listOf(it.goal, it.operation, it.explanation).filter { text -> text.isNotBlank() }.distinct().forEach { Text(it) } }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { connectionInfoExpanded = !connectionInfoExpanded }, contentPadding = PaddingValues(0.dp)) { Text(if (connectionInfoExpanded) "收起连接信息" else "更多连接信息") }
                            if (row.isNull("ended")) TextButton(onClick = { disconnect = row }) { Text("断开此连接") }
                        }
                        if (connectionInfoExpanded) {
                            Text("最近活动：${logTime(row.optLong("last_seen"))}", style = MaterialTheme.typography.bodySmall)
                            if (!row.isNull("ended")) Text("断开时间：${logTime(row.optLong("ended"))}", style = MaterialTheme.typography.bodySmall)
                            SelectionContainer { Text("连接 ID：${row.optString("owner")}", style = MaterialTheme.typography.bodySmall) }
                            if (row.optString("details") != "{}") SelectionContainer { Text(runCatching { JSONObject(row.optString("details")).toString(2) }.getOrDefault(row.optString("details")), style = MaterialTheme.typography.bodySmall) }
                        }
                    } }
                }
                item { Text("调用时间线", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)) }
                items(rows, key = { it.getString("id") }) { row ->
                    val active = row.getString("id") == returnedCall
                    Surface(onClick = { returnedCall = null; selectedCall = row.getString("id"); loading = true },
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(row.optString("tool"), style = MaterialTheme.typography.bodyMedium)
                                    Text(logTime(row.optLong("started")) + if (!row.isNull("ended")) " · ${row.optLong("ended") - row.optLong("started")} ms" else "",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text(callState(row.optString("state")), style = MaterialTheme.typography.labelSmall,
                                    color = if (row.optString("state") in listOf("completed", "running", "queued")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        }
                    }
                }
            }
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = { refresh++ }) { Text("重试") } } }
            if (!loading && error == null && rows.isEmpty() && selectedCall == null) item {
                Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.History, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (selected != null) "暂无调用记录" else if (history) "暂无历史连接" else "暂无当前连接", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (selectedCall == null && rows.size >= limit) item { TextButton(onClick = { limit += 100 }) { Text("加载更多") } }
        }
    }
    disconnect?.let { row -> AlertDialog(onDismissRequest = { if (!busy) disconnect = null }, title = { Text("断开 ${row.optString("name")}？") },
        text = { Text("释放此连接的界面占用并取消未开始的调用。Agent 会收到用户主动断开的说明，需要重新建立会话才能继续；已开始的操作以实际回执为准。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { engine.disconnect(row.getString("owner"), byUser = true) }; disconnect = null; refresh++ }
            catch (_: Exception) { error = "断开失败，请重试" }
            finally { busy = false }
        } }) { Text(if (busy) "正在断开" else "断开连接") } }, dismissButton = { TextButton(enabled = !busy, onClick = { disconnect = null }) { Text("取消") } }) }
    if (clear) AlertDialog(onDismissRequest = { if (!busy) clear = false }, title = { Text("清空本地日志？") }, text = { Text("删除历史连接和已完成的调用日志。当前连接、执行中的调用以及防止重复执行的幂等记录均会保留。") },
        confirmButton = { TextButton(enabled = !busy, onClick = { scope.launch {
            busy = true
            try { withContext(Dispatchers.IO) { engine.connectionLog.clearHistory() }; clear = false; refresh++ }
            catch (_: Exception) { error = "清空失败，请重试" }
            finally { busy = false }
        } }) { Text("清空日志") } }, dismissButton = { TextButton(enabled = !busy, onClick = { clear = false }) { Text("取消") } })
}

@Composable private fun LogPanel(title: String, body: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium); body()
    } }
}
@Composable private fun JsonLogPanel(title: String, value: String) {
    val formatted = remember(value) { runCatching { JSONObject(value).toString(2) }.getOrDefault(value) }
    LogPanel(title) { SelectionContainer { Text(formatted, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) } }
}
