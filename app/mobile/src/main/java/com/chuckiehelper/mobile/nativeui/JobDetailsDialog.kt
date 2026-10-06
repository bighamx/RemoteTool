@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun JobDetailsDialog(api: NativeApi, id: String, close: () -> Unit) {
    var detail by remember(api, id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(api, id) { mutableStateOf<String?>(null) }
    var logs by remember(api, id) { mutableStateOf(JobConsoleBuffer()) }
    var selectedAttempt by remember(id) { mutableStateOf<String?>(null) }
    var tab by remember(id) { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    var reading by remember { mutableStateOf(false) }
    var olderLoading by remember { mutableStateOf(false) }
    var follow by remember { mutableStateOf(true) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    val logScroll = rememberLazyListState()
    var autoScrolling by remember { mutableStateOf(false) }
    var userScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(logScroll, tab) {
        snapshotFlow { logScroll.isScrollInProgress }.collect { active ->
            if (active && !autoScrolling) userScrolling = true
            if (!active && userScrolling) { follow = !logScroll.canScrollForward; userScrolling = false }
        }
    }
    LaunchedEffect(api, id, tab, selectedAttempt, refresh, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var failures = 0
            while (isActive) {
                try {
                    reading = true
                    val value = api.json("/api/native-jobs/jobs/${q(id)}")
                    detail = value
                    val latest = value.array("attempts").objects().firstOrNull()?.optString("id")
                    val attempt = selectedAttempt ?: latest
                    if (tab == 1) {
                        val cursor = if (logs.attempt == attempt && attempt != null) "&offset=${logs.nextOffset}" else ""
                        val path = "/api/native-jobs/jobs/${q(id)}/console?count=200" + attempt?.let { "&attempt=${q(it)}" }.orEmpty() + cursor
                        logs = logs.merge(api.json(path))
                    }
                    error = null; failures = 0; reading = false
                    if (tab == 1 && logs.hasMore) { delay(500); continue }
                    val state = jobCurrentState(value)
                    if (!jobActive(state) || tab == 1 && selectedAttempt != null && selectedAttempt != latest) break
                    delay(if (state == "Processing") 2_000 else 5_000)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    error = e.message ?: "无法读取任务"; reading = false; failures++
                    if (e is ApiRequestFailure && e.status == 404) break
                    delay((failures * 3_000L).coerceAtMost(30_000))
                }
            }
        }
    }
    LaunchedEffect(detail?.optString("state"), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (detail?.let { jobCurrentState(it) } == "Processing") while (isActive) { now = System.currentTimeMillis(); delay(1_000) }
        }
    }
    LaunchedEffect(logs.lines.lastOrNull()?.optInt("index"), tab, follow) {
        if (tab == 1 && follow && !userScrolling) {
            autoScrolling = true
            try { repeat(2) { withFrameNanos { }; val tail = logScroll.layoutInfo.totalItemsCount - 1; if (tail >= 0) logScroll.scrollToItem(tail) } }
            finally { autoScrolling = false }
        }
    }
    Dialog(close, DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold(topBar = {
                TopAppBar(title = { Text("任务 #$id", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                    actions = { IconButton(onClick = { refresh++ }, enabled = !reading) { Icon(Icons.Outlined.Refresh, "刷新") } })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    detail?.let { job ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(job.optString("type").substringAfterLast('.'), style = MaterialTheme.typography.titleMedium)
                                Text(job.optString("method"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            JobStateBadge(jobCurrentState(job))
                        }
                    }
                    TabRow(tab) {
                        listOf("任务详情", "输出日志").forEachIndexed { index, label -> Tab(tab == index, onClick = { tab = index }, text = { Text(label) }) }
                    }
                    if (reading && detail == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    error?.let { problem -> Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(problem, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { refresh++ }) { Text("重试") }
                    } }
                    if (tab == 0) {
                        val job = detail
                        if (job == null && error == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else if (job != null) JobOverview(job, now)
                    } else {
                        var attemptsMenu by remember { mutableStateOf(false) }
                        val attempts = detail?.array("attempts")?.objects().orEmpty()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                TextButton(onClick = { attemptsMenu = true }, enabled = attempts.isNotEmpty()) {
                                    Text(if (selectedAttempt == null) "最近一次执行" else displayTime(attempts.firstOrNull { it.optString("id") == selectedAttempt }?.optString("startedAt").orEmpty()), maxLines = 1)
                                    Icon(Icons.Outlined.ExpandMore, null)
                                }
                                DropdownMenu(attemptsMenu, { attemptsMenu = false }) {
                                    DropdownMenuItem(text = { Text("最近一次执行（自动跟踪）") }, onClick = { selectedAttempt = null; attemptsMenu = false; follow = true })
                                    attempts.forEach { attempt -> DropdownMenuItem(text = { Text(displayTime(attempt.optString("startedAt"))) }, onClick = { selectedAttempt = attempt.optString("id"); attemptsMenu = false; follow = true }) }
                                }
                            }
                            TextButton(onClick = { follow = !follow }) { Icon(if (follow) Icons.Outlined.KeyboardDoubleArrowDown else Icons.Outlined.Pause, null, Modifier.size(18.dp)); Text(if (follow) "跟随" else "已暂停") }
                        }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = logScroll, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val oldest = logs.lines.firstOrNull()?.optInt("index") ?: 0
                            if (oldest > 0) item {
                                TextButton(onClick = {
                                    if (!olderLoading) scope.launch {
                                        olderLoading = true; follow = false
                                        val requestedAttempt = logs.attempt
                                        val requestedSelection = selectedAttempt
                                        try {
                                            val start = (oldest - 200).coerceAtLeast(0)
                                            val page = api.json("/api/native-jobs/jobs/${q(id)}/console?attempt=${q(requestedAttempt.orEmpty())}&offset=$start&count=${oldest - start}")
                                            if (tab == 1 && selectedAttempt == requestedSelection && logs.attempt == requestedAttempt) logs = logs.merge(page, older = true)
                                        }
                                        catch (e: CancellationException) { throw e }
                                        catch (e: Exception) { error = e.message }
                                        finally { olderLoading = false }
                                    }
                                }, enabled = !olderLoading) { Text(if (olderLoading) "正在加载…" else "加载更早日志") }
                            }
                            if (logs.lines.isEmpty()) item { Text(if (attempts.isEmpty()) "任务尚未开始执行" else "该次执行暂无日志，输出会在这里自动显示。", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            val progress = logs.lines.filter { it.optString("type") == "progress" }.associateBy { it.optString("progressId") }
                            items(logs.lines.filter { it.optString("type") != "progress" || progress[it.optString("progressId")] === it },
                                key = { "${logs.attempt}:${it.optInt("index")}" }) { line -> JobLogLine(line) }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun JobStateBadge(state: String) {
    val color = if (state == "Failed") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    Surface(color = color, shape = MaterialTheme.shapes.small) { Text(jobStateLabel(state), Modifier.padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium) }
}
@Composable private fun JobSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); content() } }
}
@Composable private fun JobField(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) { Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); SelectionContainer { Text(value.takeUnless { it.isBlank() || it == "null" } ?: "暂无", style = MaterialTheme.typography.bodySmall) } }
}
@Composable private fun JobOverview(job: JSONObject, now: Long) {
    val history = job.array("history").objects()
    val latestStart = job.array("attempts").objects().firstOrNull()?.optString("startedAt")
    val started = parseMessageTimestamp(latestStart)
    val finished = jobExecutionFinishedAt(job)
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { JobSection("执行概况") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JobField("创建时间", displayTime(job.optString("createdAt")), Modifier.weight(1f))
                JobField("执行次数", job.array("attempts").length().toString(), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JobField("开始执行", displayTime(latestStart.orEmpty()), Modifier.weight(1f))
                JobField(if (jobCurrentState(job) == "Processing") "已运行" else "执行耗时", when {
                    started == null -> "尚未开始"
                    jobCurrentState(job) == "Processing" -> formatRunElapsed(started, now)
                    finished != null -> formatRunElapsed(started, finished)
                    else -> "暂无耗时数据"
                }, Modifier.weight(1f))
            }
            job.optString("queue").takeUnless { it.isBlank() || it == "null" }?.let { JobField("队列", it) }
        } }
        item { JobSection("执行时间线") {
            history.forEachIndexed { index, state ->
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.width(20.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(if (state.optString("stateName") == "Failed") Icons.Outlined.ErrorOutline else Icons.Outlined.RadioButtonChecked, null, Modifier.size(16.dp), tint = if (state.optString("stateName") == "Failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                        if (index < history.lastIndex) Box(Modifier.padding(top = 4.dp).width(1.dp).weight(1f).background(MaterialTheme.colorScheme.outlineVariant))
                    }
                    Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
                        Row { Text(jobStateLabel(state.optString("stateName")), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge); Text(displayTime(state.optString("createdAt")), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        state.optString("reason").takeUnless { it.isBlank() || it == "null" }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        state.optJSONObject("data")?.let { data ->
                            data.optString("ExceptionMessage").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                            data.optString("ExceptionDetails").takeIf { it.isNotBlank() }?.let { trace -> var expanded by remember(trace) { mutableStateOf(false) }; TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起异常堆栈" else "查看异常堆栈") }; if (expanded) SelectionContainer { Text(trace, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } }
                        }
                    }
                }
            }
        } }
        val parameters = job.array("parameters").objects().filter { it.opt("value") != JSONObject.NULL && it.optString("type") !in listOf("PerformContext", "CancellationToken", "IJobCancellationToken") }
        if (parameters.isNotEmpty()) item { JobSection("任务参数") { parameters.forEach { parameter -> jobParameterFields(parameter.opt("value"), parameter.optString("name")).forEach { (label, value) -> JobField(label, value) } } } }
        val properties = job.optJSONObject("properties")
        if (properties != null && properties.length() > 0) item {
            var expanded by remember { mutableStateOf(false) }
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { TextButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text("其他属性", Modifier.weight(1f)); Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) }; if (expanded) jobParameterFields(properties).forEach { (label, value) -> JobField(label, value, Modifier.padding(vertical = 4.dp)) } } }
        }
    }
}
@Composable private fun JobLogLine(line: JSONObject) {
    val raw = line.optString("color")
    val color = when { raw.equals("#ff0000", true) || raw.equals("red", true) -> MaterialTheme.colorScheme.error; raw.equals("#008000", true) || raw.equals("green", true) -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurface }
    if (line.optString("type") == "progress") {
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Row { Text(line.optString("name").takeUnless { it.isBlank() || it == "null" } ?: "任务进度", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium); Text("${line.optDouble("progress").toInt()}%", style = MaterialTheme.typography.labelMedium) }
            LinearProgressIndicator(progress = { (line.optDouble("progress") / 100).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), drawStopIndicator = {})
        }
    } else {
        var expanded by remember(line.optInt("index")) { mutableStateOf(false) }
        var clipped by remember(line.optInt("index")) { mutableStateOf(false) }
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.small) {
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(displayTime(line.optString("timestamp")).substringAfter(' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectionContainer { Text(line.optString("text"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = color, maxLines = if (expanded) Int.MAX_VALUE else 12, overflow = TextOverflow.Ellipsis, onTextLayout = { clipped = it.hasVisualOverflow }) }
                if (clipped || expanded) TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开完整内容") }
                if (line.optBoolean("truncated")) Text("此行过长，仅显示前 16k 字符", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
