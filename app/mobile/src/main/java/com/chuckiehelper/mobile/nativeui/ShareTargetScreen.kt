package com.chuckiehelper.mobile.nativeui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun ShareTargetScreen(inbox: ShareInboxModel, deviceApi: NativeApi, deviceId: String, deviceName: String,
    changeDevice: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val profile = remember(deviceId) { context.getSharedPreferences("hermes-profiles-$deviceId", 0).getString("selected", "default") ?: "default" }
    val api = remember(deviceApi, profile) { deviceApi.withHermesProfile(profile) }
    var agent by rememberSaveable { mutableStateOf("codex") }
    var query by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var listError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var more by remember { mutableStateOf(false) }
    var target by remember(api.base, deviceId, agent) { mutableStateOf<JSONObject?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }
    var phoneOptions by rememberSaveable { mutableStateOf("{\"mode\":\"off\"}") }
    var createError by remember { mutableStateOf<String?>(null) }
    var createBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val busy = inbox.importing || inbox.uploading || createBusy
    BackHandler { if (!busy) inbox.dismiss() }

    suspend fun readPage(append: Boolean) {
        val requestedAgent = agent; val requestedQuery = query
        loading = true; listError = null
        try {
            val search = query.trim()
            val base = "/api/$agent/sessions" + if (search.isEmpty()) "?" else "/search?query=${android.net.Uri.encode(search)}&"
            val suffix = if (!append) "" else if (agent == "codex")
                "cursor=${android.net.Uri.encode(cursor.orEmpty())}" else "offset=$offset"
            val result = api.json(base + suffix)
            if (requestedAgent != agent || requestedQuery != query) return
            val page = result.array("data").objects()
            rows = (if (append) rows + page else page).distinctBy { it.optString("id") }
            cursor = result.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
            offset = result.optInt("offset", if (append) offset else 0) + result.optInt("limit", 50)
            more = result.optBoolean("has_more")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { listError = e.message ?: "无法读取会话列表" }
        finally { loading = false }
    }
    LaunchedEffect(api, deviceId, agent, query, refresh) {
        rows = emptyList(); cursor = null; offset = 0; more = false
        if (agent == "files") return@LaunchedEffect
        if (query.isNotBlank()) delay(300)
        readPage(false)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("分享目标", style = MaterialTheme.typography.headlineSmall)
        TextButton(onClick = changeDevice, enabled = !busy) { Text("设备：$deviceName · 切换") }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("codex" to "Codex", "hermes" to "Hermes", "files" to "文件管理").forEach { (id, name) ->
                FilterChip(selected = agent == id, onClick = { agent = id; query = ""; target = null }, enabled = !busy, label = { Text(name) })
            }
        }
        Text(if (inbox.importing) "正在读取分享文件…" else "${inbox.files.size} 个文件" +
            inbox.files.take(3).joinToString("、", prefix = if (inbox.files.isEmpty()) "" else "：") { it.name },
            style = MaterialTheme.typography.bodySmall, maxLines = 3)
        if (busy || loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        inbox.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (agent == "files") {
            ShareDirectoryTarget(inbox, api, deviceId, Modifier.weight(1f))
        } else {
        listError?.let { TextButton(onClick = { refresh++ }, enabled = !busy) { Text("$it · 重试") } }
        OutlinedTextField(inbox.text, { inbox.text = it }, label = { Text("附加消息（可选）") },
            modifier = Modifier.fillMaxWidth(), enabled = !busy, maxLines = 3)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("选择已有会话", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { createError = null; creating = true }, enabled = !busy) { Text("新建会话") }
        }
        OutlinedTextField(query, { query = it }, label = { Text("搜索会话") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rows, key = { it.getString("id") }) { row ->
                OutlinedCard(onClick = { target = row }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        RadioButton(selected = target?.optString("id") == row.optString("id"), onClick = null)
                        Column {
                            Text(row.optString("title").ifBlank { "未命名会话" }, maxLines = 2)
                            Text(row.optString("preview"), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                        }
                    }
                }
            }
            if (more) item { TextButton(onClick = { scope.launch { readPage(true) } }, enabled = !busy && !loading) { Text("加载更多会话") } }
            if (rows.isEmpty() && !loading && listError == null) item { Text("暂无会话，可新建会话") }
        }
        target?.let { Text("已选：${it.optString("title").ifBlank { "新会话" }}", style = MaterialTheme.typography.bodySmall, maxLines = 1) }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = inbox::dismiss, enabled = !busy) { Text("取消") }
            Button(onClick = { target?.let { inbox.prepare(api, deviceId, agent, it) } },
                enabled = target != null && !busy && inbox.readable && (inbox.files.isNotEmpty() || inbox.text.isNotBlank())) {
                Text(if (inbox.uploading) "正在准备附件…" else "继续到会话")
            }
        }
        }
    }
    if (creating) AlertDialog(onDismissRequest = { if (!createBusy) creating = false }, title = { Text("新建 ${if (agent == "codex") "Codex" else "Hermes"} 会话") },
        text = { Column {
            OutlinedTextField(newTitle, { newTitle = it }, label = { Text("会话名称（可选）") }, enabled = !createBusy)
            PhoneSessionOptions(api, phoneOptions) { phoneOptions = it }
            createError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        } },
        confirmButton = { TextButton(enabled = !createBusy, onClick = {
            createBusy = true; createError = null
            scope.launch {
                try {
                    val result = api.json("/api/$agent/sessions", obj("title" to newTitle, "auto_title" to newTitle.isBlank(), "phone_tools" to JSONObject(phoneOptions)))
                    target = result.getJSONObject("session"); creating = false; refresh++
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { createError = e.message ?: "新建会话失败" }
                finally { createBusy = false }
            }
        }) { Text(if (createBusy) "创建中…" else "创建") } },
        dismissButton = { TextButton(onClick = { creating = false }, enabled = !createBusy) { Text("取消") } })
}

