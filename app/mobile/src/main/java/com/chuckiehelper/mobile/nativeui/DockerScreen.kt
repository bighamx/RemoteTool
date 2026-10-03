@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun DockerScreen(api: NativeApi, onError: (String) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var doc by remember { mutableStateOf<Pair<String, String>?>(null) }
    var image by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun perform(
        path: String,
        body: JSONObject = obj(),
        after: (JSONObject) -> Unit = { refresh++ },
    ) {
        scope.launch {
            busy = true
            try {
                after(api.json(path, body))
            } catch (e: Exception) {
                onError(e.message ?: "操作失败")
            } finally {
                busy = false
            }
        }
    }
    val visibleItems = list.orEmpty()
    LaunchedEffect(api, tab, refresh) {
        if (tab < 2) {
            list = null
            try {
                list =
                    api.json("/api/docker/" + if (tab == 0) "containers" else "images")
                        .array("data")
                        .objects()
                error = null
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(tab) {
            listOf("容器", "镜像", "Compose").forEachIndexed { i, name ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(name) })
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (tab == 2) ComposeScreen(api, onError)
        else
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "${list?.size?:0} 个" + if (tab == 0) "容器" else "镜像",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "刷新") }
                    }
                }
                if (tab == 1)
                    item {
                        OutlinedCard {
                            Column(Modifier.padding(16.dp)) {
                                OutlinedTextField(
                                    image,
                                    { image = it },
                                    label = { Text("镜像名称，如 nginx:latest") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Button(
                                    enabled = image.isNotBlank() && !busy,
                                    onClick = {
                                        pending =
                                            "拉取 $image？" to
                                                {
                                                    perform(
                                                        "/api/docker/images/pull",
                                                        obj("imageTag" to image),
                                                    )
                                                }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("拉取镜像")
                                }
                            }
                        }
                    }
                if (list == null) item { ErrorPane(error) { refresh++ } }
                else
                    items(visibleItems) { item ->
                        if (tab == 0) {
                            val id = item.optString("id")
                            val running = item.optString("state") == "running"
                            ElevatedCard(Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Text(
                                        item.optString("names", id),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Text(
                                        item.optString("image"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    SuggestionChip(
                                        onClick = {},
                                        label = { Text(item.optString("status")) },
                                    )
                                    item
                                        .optString("ports")
                                        .takeIf { it.isNotBlank() }
                                        ?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall)
                                        }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilledTonalButton(
                                            enabled = !busy,
                                            onClick = {
                                                pending =
                                                    "${if(running)"停止" else "启动"}此容器？" to
                                                        {
                                                            perform(
                                                                "/api/docker/containers/${q(id)}/${if(running)"stop" else "start"}"
                                                            )
                                                        }
                                            },
                                        ) {
                                            Text(if (running) "停止" else "启动")
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                scope.launch {
                                                    try {
                                                        doc =
                                                            item.optString("names") to
                                                                api.json(
                                                                        "/api/docker/containers/${q(id)}/logs?lines=500"
                                                                    )
                                                                    .optString("logs")
                                                    } catch (e: Exception) {
                                                        onError(e.message ?: "读取失败")
                                                    }
                                                }
                                            }
                                        ) {
                                            Text("日志")
                                        }
                                        IconButton(
                                            enabled = !busy,
                                            onClick = {
                                                pending =
                                                    "删除容器 ${item.optString("names")}？" to
                                                        {
                                                            perform(
                                                                "/api/docker/containers/${q(id)}"
                                                            )
                                                        }
                                            },
                                        ) {
                                            Icon(
                                                Icons.Outlined.DeleteOutline,
                                                "删除",
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            val tag = item.optString("repository") + ":" + item.optString("tag")
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(tag, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        item.optString("size") + " · " + item.optString("created"),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row {
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                perform(
                                                    "/api/docker/images/check-update",
                                                    obj("imageTag" to tag),
                                                ) {
                                                    onError(
                                                        if (it.optBoolean("hasUpdate")) "有可用更新"
                                                        else "当前镜像没有更新"
                                                    )
                                                }
                                            },
                                        ) {
                                            Text("检查更新")
                                        }
                                        TextButton(
                                            enabled = !busy,
                                            onClick = {
                                                pending =
                                                    "拉取 $tag？" to
                                                        {
                                                            perform(
                                                                "/api/docker/images/pull",
                                                                obj("imageTag" to tag),
                                                            )
                                                        }
                                            },
                                        ) {
                                            Text("拉取")
                                        }
                                    }
                                }
                            }
                        }
                    }
            }
    }
    pending?.let { (message, action) ->
        ConfirmDialog("Docker 操作", message, { pending = null }) {
            pending = null
            action()
        }
    }
    doc?.let { (title, text) -> TextDocument(title, text, false, { doc = null }) {} }
}

@Composable
fun ComposeScreen(api: NativeApi, onError: (String) -> Unit, initialPath: String? = null) {
    var path by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    var projects by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var output by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var chooseFile by remember { mutableStateOf(false) }
    var selectedProject by remember { mutableStateOf<String?>(null) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(api) {
        try {
            projects = api.json("/api/docker/compose/status").array("data").objects()
        } catch (e: Exception) {
            onError(e.message ?: "项目读取失败")
        }
    }
    fun task(action: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "操作失败")
            } finally {
                busy = false
            }
        }
    }
    fun openFile(target: String, project: String? = null) {
        task {
            path = target
            selectedProject = project
            content = api.json("/api/docker/compose/read?path=${q(target)}").optString("content")
            listState.animateScrollToItem(0)
        }
    }
    LaunchedEffect(initialPath) { if (initialPath != null) openFile(initialPath) }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            selectedProject?.let { Text("正在管理：$it", style = MaterialTheme.typography.titleMedium) }
            OutlinedTextField(
                path,
                { path = it },
                label = { Text("服务器上的 Compose 文件路径") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            FlowRowCompat {
                OutlinedButton(enabled = !busy, onClick = { chooseFile = true }) {
                    Icon(Icons.Outlined.FolderOpen, null)
                    Text("浏览配置文件")
                }
                OutlinedButton(
                    enabled = !busy && path.isNotBlank(),
                    onClick = {
                        task {
                            content =
                                api.json("/api/docker/compose/read?path=${q(path)}")
                                    .optString("content")
                        }
                    },
                ) {
                    Text("读取")
                }
                OutlinedButton(
                    enabled = !busy && content.isNotBlank(),
                    onClick = {
                        task {
                            onError(
                                api.json(
                                        "/api/docker/compose/validate",
                                        obj("path" to path, "content" to content),
                                    )
                                    .optString("message", "验证通过")
                            )
                        }
                    },
                ) {
                    Text("验证")
                }
                Button(enabled = !busy && path.isNotBlank(), onClick = { confirm = "write" }) {
                    Text("保存")
                }
            }
        }
        item {
            OutlinedTextField(
                content,
                { content = it },
                label = { Text("Compose YAML") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 420.dp),
                textStyle =
                    MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        item {
            FlowRowCompat {
                listOf("up" to "启动", "pull" to "拉取", "stop" to "停止", "down" to "移除项目").forEach {
                    (action, label) ->
                    OutlinedButton(
                        enabled = !busy && path.isNotBlank(),
                        onClick = { confirm = action },
                    ) {
                        Text(label)
                    }
                }
            }
        }
        if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(projects) { p ->
            OutlinedCard(
                onClick = {
                    val config =
                        p.optString("configFiles").split(',').firstOrNull()?.trim().orEmpty()
                    if (config.isNotBlank()) openFile(config, p.optString("name"))
                    else onError("此项目没有可读取的配置文件路径")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(p.optString("name"), style = MaterialTheme.typography.titleMedium)
                    Text(p.optString("status"), style = MaterialTheme.typography.bodySmall)
                    Text(p.optString("configFiles"), style = MaterialTheme.typography.bodySmall)
                    Text(
                        "点击读取配置并管理",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
    confirm?.let { action ->
        ConfirmDialog(
            "Compose",
            "${if(action=="write")"保存文件" else "执行 $action"}：$path？",
            { confirm = null },
        ) {
            confirm = null
            task {
                if (action == "write") {
                    api.json("/api/docker/compose/write", obj("path" to path, "content" to content))
                    onError("已保存")
                } else {
                    output = ""
                    api.stream("/api/docker/compose/$action/stream", obj("composePath" to path)) {
                        chunk ->
                        output = (output.orEmpty() + chunk).takeLast(250000)
                    }
                }
            }
        }
    }
    output?.let { TextDocument("Compose 输出", it, false, { if (!busy) output = null }) {} }
    if (chooseFile)
        PathPicker(api, "选择 Compose 配置", parentPath(path), false, { chooseFile = false }) { target
            ->
            chooseFile = false
            openFile(target)
        }
}
