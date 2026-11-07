@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
internal fun HermesAdminScreen(api: NativeApi, device: String, profile: String, onModels: () -> Unit,
    onProviders: () -> Unit, onTitle: () -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var document by rememberSaveable(stateSaver=Saver<JSONObject?,String>(save={it?.toString().orEmpty()},restore={it.takeIf{it.isNotEmpty()}?.let(::JSONObject)})) { mutableStateOf<JSONObject?>(null) }
    var content by rememberSaveable { mutableStateOf("") }
    var saved by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf(false) }
    var conflict by remember { mutableStateOf(false) }
    var gateway by remember { mutableStateOf(JSONObject()) }
    var updateInfo by remember { mutableStateOf(JSONObject()) }
    var logText by remember { mutableStateOf("") }
    var logKey by remember { mutableStateOf("") }
    var logOffset by remember { mutableLongStateOf(0L) }
    var followLog by remember { mutableStateOf(true) }
    val logScroll = rememberScrollState()
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var backups by remember { mutableStateOf(false) }
    var patches by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var patchBusy by remember { mutableStateOf(false) }
    var patchLog by remember { mutableStateOf("") }
    var patchOffer by remember { mutableStateOf<JSONObject?>(null) }
    var patchPrompt by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    val dirty = content != saved
    fun run(action: suspend () -> Unit) { scope.launch {
        busy = true; error = null
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message; if (failure is ApiRequestFailure && failure.status == 409 && failure.code == "file_changed") conflict = true }
        finally { busy = false }
    } }
    fun accept(value: JSONObject) { document = value; content = value.optString("content"); saved = content; conflict = false; preview = false }
    fun leave(action: () -> Unit) { if (!busy) { if (dirty) confirm = "放弃未保存的修改？" to action else action() } }
    fun goBack() { leave {
        if (document != null) {
            document = null; content = ""; saved = ""; error = null; conflict = false; preview = false; backups = false
        } else onClose()
    } }
    fun save(overwrite: Boolean = false, backup: String? = null) {
        val doc = document ?: return
        run {
            val body = obj("revision" to doc.optString("revision"), "content" to content, "overwrite" to overwrite)
            if (backup != null) body.put("backup_id", backup)
            accept(api.json("/api/hermes/admin/files/${doc.getString("id")}", body))
        }
    }
    fun refreshPatches(prompt: Boolean = false) { scope.launch {
        try {
            val rows = api.json("/api/hermes/admin/patches").array("patches").objects()
            patches = rows
            if (prompt) {
                val ready = rows.filter { it.optString("state") == "ready" }
                if (ready.isNotEmpty()) patchPrompt = ready
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { patchLog = failure.message ?: "补丁清单读取失败" }
    } }
    fun applyPatch(item: JSONObject) { scope.launch {
        patchBusy = true; patchLog = ""
        try {
            val result = api.json("/api/hermes/admin/patches/${item.optString("id")}/apply", obj())
            patchLog = listOf(result.optString("message"), result.optString("log")).filter { it.isNotBlank() }.joinToString("\n")
            patchOffer = if (result.optBoolean("needs_agent")) JSONObject(item.toString()).put("_log", result.optString("log")) else null
            refreshPatches()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { patchLog = failure.message ?: "补丁应用失败" }
        finally { patchBusy = false }
    } }
    fun agentFixPatch() { val item = patchOffer ?: return; scope.launch {
        patchBusy = true
        try {
            val result = api.json("/api/hermes/admin/patches/${item.optString("id")}/agent-fix", obj("log" to item.optString("_log")))
            patchLog = result.optString("message") + if (result.optString("run_id").isNotBlank()) "\n运行 ${result.optString("run_id")}" else ""
            patchOffer = null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { patchLog = failure.message ?: "无法启动 Hermes 会话" }
        finally { patchBusy = false }
    } }
    LaunchedEffect(api) {
        try { files = api.json("/api/hermes/admin/files").array("data").objects() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
        refreshPatches(prompt = true)
        try { updateInfo = api.json("/api/hermes/admin/gateway/update") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { updateInfo = JSONObject() }
        while (true) {
            try {
                gateway = api.json("/api/hermes/admin/gateway")
                val chunk = api.json("/api/hermes/admin/gateway/log?offset=$logOffset&key=$logKey")
                if (chunk.optString("key") != logKey) { logText = ""; logKey = chunk.optString("key") }
                logText = (logText + chunk.optString("text")).takeLast(120000)
                logOffset = chunk.optLong("offset")
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { gateway = obj("read_error" to (failure.message ?: "网关状态读取失败")) }
            delay(2000)
        }
    }
    LaunchedEffect(logText, logScroll.maxValue, followLog) { if (followLog) logScroll.scrollTo(logScroll.maxValue) }
    BackHandler { goBack() }
    Scaffold(topBar={TopAppBar(title={Text(if(document==null)"Hermes 管理" else files.firstOrNull{it.optString("id")==document?.optString("id")}?.optString("name")?:"文件编辑")},
        navigationIcon={IconButton(onClick=::goBack,enabled=!busy){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回")}})}) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp,vertical=8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("$device · $profile", style = MaterialTheme.typography.labelMedium)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (document == null) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Profile 配置", style = MaterialTheme.typography.titleSmall)
                    val entries = buildList<Pair<String, () -> Unit>> {
                        files.forEach { file -> add(file.optString("name") to { run { accept(api.json("/api/hermes/admin/files/${file.getString("id")}")) } }) }
                        add("默认模型" to onModels); add("Provider 管理" to onProviders); add("自动标题" to onTitle)
                    }
                    entries.chunked(2).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (name, action) -> OutlinedButton(onClick = action, enabled = !busy, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) { Text(name, maxLines = 1, style = MaterialTheme.typography.bodySmall) } }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("补丁", style = MaterialTheme.typography.titleSmall)
                        Text("待应用 ${patches.count { it.optString("state") != "applied" }}", style = MaterialTheme.typography.labelSmall)
                    }
                    if (patches.isEmpty()) Text("没有补丁清单", style = MaterialTheme.typography.bodySmall)
                    patches.forEach { item ->
                        val state = item.optString("state")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(item.optString("title").ifBlank { item.optString("id") }, style = MaterialTheme.typography.bodyMedium)
                                val summary = item.optString("summary")
                                if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                                Text(item.optString("id"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(
                                when (state) { "applied" -> "已应用"; "ready" -> "可应用"; else -> "需处理" },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (state) {
                                    "applied" -> MaterialTheme.colorScheme.primary
                                    "ready" -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.error
                                },
                            )
                            if (state != "applied") OutlinedButton(
                                enabled = !patchBusy && !busy,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                onClick = {
                                    val label = item.optString("title").ifBlank { item.optString("id") }
                                    confirm = "应用补丁「$label」？会先备份工作树差异，失败自动回滚。" to { applyPatch(item) }
                                },
                            ) { Text("应用", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    patchOffer?.let { offer ->
                        Text("「${offer.optString("title")}」无法直接应用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(enabled = !patchBusy, onClick = { agentFixPatch() }) { Text("让 Hermes 自行实现") }
                    }
                    if (patchLog.isNotBlank()) Text(patchLog, style = MaterialTheme.typography.labelSmall)
                    HorizontalDivider()
                    Text("网关管理 · 影响全部 Profile", style = MaterialTheme.typography.titleSmall)
                    val live = gateway.optJSONObject("gateway")
                    val gatewayState = when {
                        gateway.optString("read_error").isNotBlank() || live == null -> "状态不可用"
                        !live.optBoolean("running") -> "已停止"
                        !live.optBoolean("fresh") -> "进程存在，心跳已过期"
                        else -> when (val state = live.optString("state")) {
                            "running" -> "运行中"
                            "draining" -> "排空中"
                            "starting" -> "启动中"
                            "stopping" -> "停止中"
                            else -> state.takeIf { it.isNotBlank() && it != "null" } ?: "状态未知"
                        }
                    }
                    Text(gatewayState + if (live != null && gateway.optString("read_error").isBlank() && live.optBoolean("running") && live.optBoolean("fresh")) " · 活跃任务 ${live.optInt("active")} · PID ${live.optInt("pid")}" else "")
                    hermAdminVersionLine(updateInfo)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    gateway.optString("read_error").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    val phase = gateway.optString("phase")
                    val canStart = !busy && phase !in listOf("queued", "checking", "waiting", "updating", "restarting") && live?.optBoolean("restart_supported") == true && live.optBoolean("fresh") && live.optBoolean("running")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp), enabled = canStart && live?.optInt("active") == 0, onClick = {
                        confirm = "重启当前设备的网关？将影响全部 Profile。" to { run { gateway = api.json("/api/hermes/admin/gateway/restart", obj("wait" to false)) } }
                    }) { Text("立即重启", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp), enabled = canStart, onClick = {
                        confirm = "暂停接收新任务，等待全部 Profile 空闲后重启？" to { run { gateway = api.json("/api/hermes/admin/gateway/restart", obj("wait" to true)) } }
                    }) { Text("空闲后重启", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(6.dp), enabled = canStart, onClick = {
                        confirm = "等待全部 Profile 空闲后按官方流程更新并重启？将自动确认恢复本地修改。" to { run { gateway = api.json("/api/hermes/admin/gateway/update", obj()) } }
                    }) { Text("更新 Hermes", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    }
                    if (gateway.optBoolean("can_cancel")) TextButton(onClick = { run { gateway = api.json("/api/hermes/admin/gateway/cancel", obj()) } }, enabled = !busy) { Text("取消等待") }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("维护日志", style = MaterialTheme.typography.titleSmall)
                        FilterChip(selected = followLog, onClick = { followLog = !followLog }, label = { Text("跟随最新") })
                    }
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Box(Modifier.fillMaxWidth().height(240.dp).verticalScroll(logScroll)) {
                            Text(logText.ifBlank { "暂无维护日志" }, style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        }
                    }
                } else {
                    val filePath = document!!.optString("fullPath").ifBlank {
                        document!!.optString("path").ifBlank {
                            files.firstOrNull { it.optString("id") == document!!.optString("id") }?.optString("path").orEmpty()
                        }
                    }
                    if (filePath.isNotBlank()) AdminFilePath(filePath)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { preview = !preview }) { Text(if (preview) "编辑" else "预览") }
                        TextButton(onClick = { leave { val id = document!!.getString("id"); run { accept(api.json("/api/hermes/admin/files/$id")) } } }, enabled = !busy) { Text("重新加载") }
                        TextButton(onClick = { backups = true }, enabled = !busy) { Text("备份") }
                        Button(onClick = { save() }, enabled = dirty && !busy) { Text("保存") }
                    }
                    Text("保存后由 Hermes 在后续加载时采用，运行中的任务不会立即更新。", style = MaterialTheme.typography.bodySmall)
                    if (conflict) TextButton(onClick = { confirm = "覆盖电脑上的最新版本？覆盖前会备份。" to { save(true) } }, enabled = !busy) { Text("覆盖保存") }
                    val editorStyle = MaterialTheme.typography.bodyLarge.let {
                        it.copy(fontSize = it.fontSize * 0.5f, lineHeight = it.lineHeight * 0.5f)
                    }
                    if (preview) Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { HermesMarkdown(content) }
                    else if (isHermesEntryFile(document!!.optString("id"), content)) key(document!!.optString("id"), document!!.optString("revision")) {
                        HermesMemoryEntries(content, { content = it }, !busy, editorStyle, Modifier.fillMaxWidth().weight(1f))
                    }
                    else OutlinedTextField(content, { content = it }, Modifier.fillMaxWidth().weight(1f), enabled = !busy,
                        textStyle = editorStyle, label = { Text(if (document!!.optBoolean("exists")) "内容" else "尚未创建") })
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
    }
    confirm?.let { pending -> AlertDialog(onDismissRequest = { confirm = null }, text = { Text(pending.first) }, confirmButton = { TextButton(onClick = { confirm = null; pending.second() }) { Text("确定") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } }) }
    if (patchPrompt.isNotEmpty()) AlertDialog(
        onDismissRequest = { patchPrompt = emptyList() },
        title = { Text("检测到可应用补丁") },
        text = { Text("以下补丁尚未应用：\n" + patchPrompt.joinToString("\n") { "· " + it.optString("title").ifBlank { it.optString("id") } } + "\n\n应用会先备份工作树差异，失败自动回滚；语法检查不过也会回滚。") },
        confirmButton = { TextButton(onClick = { val todo = patchPrompt; patchPrompt = emptyList(); todo.firstOrNull()?.let { applyPatch(it) } }) { Text("应用") } },
        dismissButton = { TextButton(onClick = { patchPrompt = emptyList() }) { Text("稍后") } },
    )
    if (backups) AlertDialog(onDismissRequest = { backups = false }, title = { Text("文件备份") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            val rows = document?.array("backups")?.objects().orEmpty()
            if (rows.isEmpty()) Text("暂无备份")
            rows.forEach { backup -> TextButton(onClick = {
                backups = false
                confirm = "恢复此备份？当前文件将先备份，未保存的编辑将被替换。" to { save(backup = backup.getString("id")) }
            }) { Text(backup.optString("at")) } }
        }
    }, confirmButton = { TextButton(onClick = { backups = false }) { Text("关闭") } })
}

/** Hermes 自身的版本检测结果：源码安装看落后提交，包安装比 PyPI 版本。 */
private fun hermAdminVersionLine(info: JSONObject): String? = when (info.optString("kind")) {
    "source" -> when {
        info.optBoolean("unknown") -> "源码安装 · 版本检测暂不可用（网络或 Git 访问受限）"
        info.optInt("behind") > 0 -> "源码安装 · 落后 ${info.optInt("behind")} 个提交，可更新"
        else -> "源码安装 · 已是最新"
    }
    "package" -> buildString {
        append("包安装 · 已装 ").append(info.optString("installed").ifBlank { "未知" })
        info.optString("latest").takeIf { it.isNotBlank() }?.let { append(" · 最新 ").append(it) }
        if (info.optBoolean("updateAvailable")) append("（可更新）")
    }
    "none" -> "未检测到本机 Hermes 安装"
    else -> null
}
