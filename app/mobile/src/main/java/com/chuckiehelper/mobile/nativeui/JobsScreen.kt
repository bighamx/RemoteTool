package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun JobsScreen(api: NativeApi, onError: (String) -> Unit) {
    var overview by remember { mutableStateOf<JSONObject?>(null) }
    var jobs by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var tab by rememberSaveable { mutableStateOf("recurring") }
    var queue by rememberSaveable { mutableStateOf("default") }
    var offset by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var cron by remember { mutableStateOf<JSONObject?>(null) }
    var detail by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val tabs =
        listOf(
            "recurring" to "定时",
            "failed" to "失败",
            "processing" to "执行中",
            "enqueued" to "排队",
            "scheduled" to "延迟",
            "succeeded" to "完成",
            "deleted" to "删除",
            "servers" to "服务",
        )
    LaunchedEffect(api, refresh, tab, offset, queue) {
        try {
            overview = api.json("/api/native-jobs/overview")
            if (tab !in listOf("recurring", "servers"))
                jobs =
                    api.json("/api/native-jobs/jobs?state=$tab&queue=${q(queue)}&offset=$offset")
                        .array("data")
                        .objects()
            error = null
        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            error = e.message
        }
    }
    fun action(path: String, body: JSONObject = obj()) {
        scope.launch {
            try {
                api.json(path, body)
                refresh++
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                onError(e.message ?: "操作失败")
            }
        }
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FlowRowCompat {
                tabs.forEach { (state, title) ->
                    FilterChip(
                        tab == state,
                        {
                            tab = state
                            offset = 0
                        },
                        label = { Text(title) },
                    )
                }
            }
        }
        item {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Hangfire", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "刷新") }
            }
        }
        overview?.optJSONObject("stats")?.let { stats ->
            item {
                FlowRowCompat {
                    listOf(
                            "enqueued" to "排队",
                            "processing" to "执行",
                            "failed" to "失败",
                            "succeeded" to "完成",
                            "scheduled" to "延迟",
                        )
                        .forEach { (state, label) ->
                            SuggestionChip(
                                onClick = {
                                    tab = state
                                    offset = 0
                                },
                                label = { Text("$label ${stats.optLong(state)}") },
                            )
                        }
                }
            }
        }
        if (error != null) item { ErrorPane(error) { refresh++ } }
        if (overview == null && error == null) item { ErrorPane(null) { refresh++ } }
        if (tab == "recurring")
            items(overview?.array("recurring")?.objects().orEmpty()) { job ->
                val id = job.optString("id")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(id, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${job.optString("cron")} · ${job.optString("timeZoneId")}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "下次（手机时间）：${displayTime(job.optString("nextExecution"))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            "最近状态：${job.optString("lastJobState").takeUnless{it=="null"||it.isBlank()} ?: "尚未执行"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        job.optString("error")
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?.let {
                                Text(
                                    it,
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        FlowRowCompat {
                            TextButton(
                                onClick = {
                                    pending = "立即触发 $id？" to { action("/api/job/${q(id)}/trigger") }
                                }
                            ) {
                                Text("立即执行")
                            }
                            TextButton(onClick = { cron = job }) { Text("修改计划") }
                            TextButton(
                                onClick = {
                                    pending =
                                        "移除定时任务 $id？" to
                                            {
                                                action("/api/native-jobs/recurring/${q(id)}/delete")
                                            }
                                }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        else if (tab == "servers") {
            items(overview?.array("servers")?.objects().orEmpty()) { server ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { InfoRows(server) }
                }
            }
            items(overview?.array("queues")?.objects().orEmpty()) { row ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { InfoRows(row) }
                }
            }
        } else {
            if (tab == "enqueued")
                item {
                    OutlinedTextField(
                        queue,
                        {
                            queue = it
                            offset = 0
                        },
                        label = { Text("队列名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            items(jobs, key = { it.optString("id") }) { job ->
                val id = job.optString("id")
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("# $id", style = MaterialTheme.typography.titleMedium)
                        Text(
                            job.optString("type") + "." + job.optString("method"),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            detail =
                                                "任务 $id" to
                                                    api.json("/api/native-jobs/jobs/${q(id)}")
                                                        .toString(2)
                                        } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                                            onError(e.message ?: "读取失败")
                                        }
                                    }
                                }
                            ) {
                                Text("详情")
                            }
                            TextButton(
                                onClick = {
                                    pending =
                                        "重新排队任务 $id？" to
                                            {
                                                action("/api/native-jobs/jobs/${q(id)}/retry")
                                            }
                                }
                            ) {
                                Text("重试")
                            }
                            TextButton(
                                onClick = {
                                    pending =
                                        "删除任务 $id？" to
                                            {
                                                action("/api/native-jobs/jobs/${q(id)}/delete")
                                            }
                                }
                            ) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            item {
                Row {
                    TextButton(
                        enabled = offset > 0,
                        onClick = { offset = (offset - 30).coerceAtLeast(0) },
                    ) {
                        Text("上一页")
                    }
                    Text("第 ${offset/30+1} 页", Modifier.padding(12.dp))
                    TextButton(enabled = jobs.size >= 30, onClick = { offset += 30 }) {
                        Text("下一页")
                    }
                }
            }
        }
    }
    pending?.let { (message, perform) ->
        ConfirmDialog("任务操作", message, { pending = null }) {
            pending = null
            perform()
        }
    }
    cron?.let { job ->
        InputDialog("修改 Cron", "Cron 表达式", job.optString("cron"), { cron = null }) { value ->
            pending =
                "将 ${job.optString("id")} 的计划修改为 $value？" to
                    {
                        action(
                            "/api/native-jobs/recurring/${q(job.optString("id"))}/update",
                            obj("cron" to value, "timeZoneId" to job.optString("timeZoneId")),
                        )
                    }
            cron = null
        }
    }
    detail?.let { (title, text) -> TextDocument(title, text, false, { detail = null }) {} }
}
