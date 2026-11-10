@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private fun JSONArray?.claudeRows(): List<JSONObject> = (0 until (this?.length() ?: 0)).mapNotNull { this?.optJSONObject(it) }
private val claudeTerminal = setOf("completed", "failed", "cancelled", "acceptance_unknown")
private fun claudeText(value: Any?): String = when (value) {
    is String -> value
    is JSONArray -> value.claudeRows().mapNotNull { block -> when (block.optString("type")) {
        "text" -> block.optString("text")
        "tool_use" -> "工具 · ${block.optString("name")}\n${block.opt("input")}"
        "tool_result" -> "工具结果\n${claudeText(block.opt("content"))}"
        "thinking" -> null
        else -> "[${block.optString("type", "消息")}]"
    } }.joinToString("\n")
    else -> ""
}

private class ClaudeModel(private val api: NativeApi, private val preferences: SharedPreferences) : ViewModel() {
    var sessions by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var selected by mutableStateOf<JSONObject?>(null); private set
    var messages by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var events by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var run by mutableStateOf<JSONObject?>(null); private set
    var setup by mutableStateOf(JSONObject()); private set
    var error by mutableStateOf(""); private set
    var refreshing by mutableStateOf(false); private set
    var loadingSession by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var acting by mutableStateOf(false); private set
    var draft by mutableStateOf(""); private set
    var pending by mutableStateOf<JSONObject?>(null); private set
    var answers by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var creating by mutableStateOf(false)
    var createError by mutableStateOf(""); private set
    private var epoch = 0
    private var cursor = 0L
    private var polling = false
    private var synced = false
    val request get() = run?.optJSONObject("request")
    val running get() = run != null && run!!.optString("status") !in claudeTerminal
    val status get() = if (loadingSession) "加载会话…" else if (pending != null) "发送结果待核对" else when (run?.optString("status")) {
        "started" -> "执行中"; "waiting" -> "等待确认"; "completed" -> "已完成"; "cancelled" -> "已停止"
        "acceptance_unknown" -> "结果待核对"; "failed" -> "未完成"; else -> ""
    }
    private fun launch(action: suspend () -> Unit) = viewModelScope.launch {
        try { action() } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { error = e.message ?: "Claude 接口暂不可用，请更新服务端或检查配置" }
    }
    fun editDraft(value: String) { draft = value; selected?.optString("id")?.let { preferences.edit().putString("draft-$it", value).apply() } }
    fun answer(question: String, value: String) { answers = answers + (question to value) }
    fun choose(question: JSONObject, value: String) {
        val key = question.optString("question")
        if (!question.optBoolean("multiSelect")) { answer(key, value); return }
        val values = answers[key].orEmpty().split(", ").filter { it.isNotBlank() }.toMutableSet()
        if (!values.add(value)) values.remove(value)
        answer(key, values.joinToString(", "))
    }
    fun refresh() { if (refreshing) return; refreshing = true; launch {
        try { setup = api.json("/api/claude/setup"); sessions = api.json("/api/claude/sessions").optJSONArray("data").claudeRows(); error = "" }
        finally { refreshing = false }
    } }
    fun back() { epoch++; selected = null }
    fun select(row: JSONObject) { val mine = ++epoch; loadingSession = true; selected = row; messages = emptyList(); events = emptyList(); run = null; cursor = 0; synced = false; answers = emptyMap(); error = ""
        val id = row.optString("id"); draft = preferences.getString("draft-$id", "").orEmpty()
        pending = preferences.getString("pending-$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        launch { try { val history = api.json("/api/claude/sessions/$id/messages"); val active = api.json("/api/claude/sessions/$id/active-run")
            if (mine != epoch) return@launch
            messages = history.optJSONArray("data").claudeRows(); run = active.takeIf { it.has("id") }; if (pending != null) checkReceipt()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { if (mine == epoch) error = e.message ?: "读取会话失败" }
        finally { if (mine == epoch) loadingSession = false } }
    }
    fun create(cwd: String, title: String, permission: String) { if (sending) return; sending = true; createError = ""; launch {
        try { val row = api.json("/api/claude/sessions", JSONObject().put("cwd", cwd).put("title", title).put("permission_mode", permission)); sessions = listOf(row) + sessions; creating = false; select(row) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { createError = e.message ?: "创建失败" }
        finally { sending = false }
    } }
    private fun accept(value: JSONObject) { run = value; pending = null; events = emptyList(); cursor = 0; synced = false; answers = emptyMap(); val id = value.optString("session_id"); preferences.edit().remove("pending-$id").remove("draft-$id").apply(); draft = "" }
    fun send() { val row = selected ?: return; if (loadingSession || sending || running || pending != null || draft.isBlank() || !row.optBoolean("managed") || run?.optString("status") == "acceptance_unknown") return
        val id = row.optString("id"); val mine = epoch; val input = draft; val key = UUID.randomUUID().toString()
        pending = JSONObject().put("key", key).put("input", input); preferences.edit().putString("pending-$id", pending.toString()).commit(); sending = true
        launch { try { val body = JSONObject().put("session_id", id).put("input", input)
            val result = api.json(api.request("/api/claude/runs", body).newBuilder().header("Idempotency-Key", key).build())
            if (mine == epoch) accept(result)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { if (mine == epoch) { if (e is ApiRequestFailure && e.delivery == "rejected") { pending = null; preferences.edit().remove("pending-$id").apply() }; error = "${e.message}；请先核对发送回执，未自动重发" } }
        finally { sending = false } }
    }
    fun checkReceipt() { val current = pending ?: return; val mine = epoch; launch {
        val result = api.json("/api/claude/receipts/${current.optString("key")}")
        if (mine == epoch && pending?.optString("key") == current.optString("key")) { if (result.has("id")) accept(result) else error = "尚未找到回执，请稍后核对；未自动重发" }
    } }
    suspend fun poll() { val active = run ?: return; if (polling || selected == null || synced) return; polling = true; val mine = epoch; val id = active.optString("id")
        try { val status = api.json("/api/claude/runs/$id"); val page = api.json("/api/claude/runs/$id/events?after=$cursor")
            if (mine != epoch || run?.optString("id") != id) return
            if (status.optJSONObject("request")?.optString("id") != request?.optString("id")) answers = emptyMap()
            run = status; events = events + page.optJSONArray("data").claudeRows(); cursor = page.optLong("cursor")
            if (status.optString("status") in claudeTerminal && cursor == status.optLong("sequence")) {
                val history = api.json("/api/claude/sessions/${status.optString("session_id")}/messages").optJSONArray("data").claudeRows()
                if (mine != epoch || run?.optString("id") != id) return
                val last = events.lastOrNull { it.optString("type") == "assistant" }?.optJSONObject("data")?.optString("id")
                if (!last.isNullOrBlank() && history.any { it.optString("id") == last }) { messages = history; events = emptyList(); synced = true }
                else if (events.isEmpty()) { messages = history; synced = true }
                else if (last.isNullOrBlank()) synced = true
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { if (mine == epoch) error = e.message ?: "读取失败" }
        finally { polling = false }
    }
    fun reply(allow: Boolean) { val current = request ?: return; val id = run?.optString("id") ?: return; if (acting) return; acting = true; val mine = epoch
        launch { try { api.json("/api/claude/runs/$id/reply", JSONObject().put("request_id", current.optString("id")).put("allow", allow).put("answers", JSONObject(answers))); if (mine == epoch && run?.optString("id") == id && request?.optString("id") == current.optString("id")) { run = JSONObject(run.toString()).put("request", JSONObject.NULL); answers = emptyMap() } }
            finally { acting = false } }
    }
    fun stop() { val id = run?.optString("id") ?: return; if (acting) return; acting = true; launch { try { api.json("/api/claude/runs/$id/stop", JSONObject()) } finally { acting = false } } }
    val streamText get(): String { val out = StringBuilder(); events.forEach { row ->
        if (row.optString("type") == "assistant") out.clear()
        if (row.optString("type") == "stream") row.optJSONObject("data")?.optJSONObject("delta")?.takeIf { it.optString("type") == "text_delta" }?.let { out.append(it.optString("text")) }
    }; return out.toString() }
}

@Composable
internal fun ClaudeScreen(api: NativeApi, deviceId: String, onClose: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val model: ClaudeModel = viewModel(key = "claude-$deviceId", factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = ClaudeModel(api, context.getSharedPreferences("claude-$deviceId", 0)) as T
    })
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(model) { model.refresh() }
    LaunchedEffect(model, lifecycle) { lifecycle.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { while (isActive) { model.poll(); delay(1000) } } }
    BackHandler(model.selected != null) { model.back() }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (model.selected == null) onClose() else model.back() }) { Text("返回") }
            Text("Claude Code", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { model.refresh() }, enabled = !model.refreshing) { Text("刷新") }
            TextButton(onClick = { model.creating = true }) { Text("新建") }
        }
        if (model.error.isNotBlank()) Text(model.error, Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
        if (model.selected == null) {
            Text(model.setup.optString("message", "检查官方 Claude Code…"), Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f)) { items(model.sessions, key = { it.optString("id") }) { row ->
                ListItem(headlineContent = { Text(row.optString("title")) }, supportingContent = { Text("${row.optString("cwd")}\n${if (row.optBoolean("managed")) "RemoteTool 会话" else "本机会话 · 只读"}") }, trailingContent = { TextButton(onClick = { model.select(row) }) { Text("打开") } })
            } }
        } else {
            Text("${model.selected!!.optString("title")} · ${model.status}", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.titleSmall)
            if (model.selected!!.optBoolean("managed")) Text("权限：${model.selected!!.optString("permission_mode")} · 本机项目规则", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
            if (!model.selected!!.optBoolean("managed")) Text("本机会话仅供查看；请新建 RemoteTool 会话以远程执行", Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(model.messages, key = { it.optString("id") }) { row -> SelectionContainer { Text("${row.optString("role")}\n${row.optString("content").ifBlank { claudeText(row.opt("blocks")) }}", style = MaterialTheme.typography.bodyMedium) } }
                if (model.events.isNotEmpty()) item { Text("当前任务", style = MaterialTheme.typography.labelSmall) }
                items(model.events.filter { it.optString("type") in listOf("user", "assistant", "tool_result") }, key = { "event-${it.optLong("seq")}" }) { row ->
                    val data = row.optJSONObject("data") ?: JSONObject()
                    SelectionContainer { Text(data.optString("text").ifBlank { claudeText(data.opt("blocks")) }, style = MaterialTheme.typography.bodyMedium) }
                }
                if (model.streamText.isNotBlank()) item { SelectionContainer { Text(model.streamText, style = MaterialTheme.typography.bodyMedium) } }
                model.run?.optString("error")?.takeIf { it.isNotBlank() }?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
                model.run?.optJSONObject("usage")?.let { usage -> item { Text("Token：${usage.optJSONObject("tokens")?.opt("input_tokens") ?: "—"} 输入 / ${usage.optJSONObject("tokens")?.opt("output_tokens") ?: "—"} 输出 · 参考费用 $${usage.opt("cost_usd")?.takeUnless { it == JSONObject.NULL } ?: "—"}", style = MaterialTheme.typography.labelSmall) } }
            }
            model.request?.let { request ->
                Column(Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(8.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text("等待确认 · ${request.optString("tool")}", style = MaterialTheme.typography.titleSmall)
                    if (request.optString("tool") == "AskUserQuestion") {
                        request.optJSONObject("input")?.optJSONArray("questions").claudeRows().forEach { q ->
                            val key = q.optString("question")
                            OutlinedTextField(value = model.answers[key].orEmpty(), onValueChange = { model.answer(key, it) }, label = { Text(key) }, modifier = Modifier.fillMaxWidth())
                            q.optJSONArray("options").claudeRows().forEach { option -> TextButton(onClick = { model.choose(q, option.optString("label")) }) { Text(option.optString("label")) } }
                        }
                    } else SelectionContainer { Text(request.optJSONObject("input")?.toString(2).orEmpty(), style = MaterialTheme.typography.bodySmall) }
                    Row { TextButton(onClick = { model.reply(true) }, enabled = !model.acting) { Text(if (request.optString("tool") == "AskUserQuestion") "提交回答" else "允许本次") }; TextButton(onClick = { model.reply(false) }, enabled = !model.acting) { Text("拒绝") } }
                }
            }
            if (model.running) TextButton(onClick = { model.stop() }, enabled = !model.acting) { Text("停止任务") }
            if (model.pending != null) TextButton(onClick = { model.checkReceipt() }) { Text("发送结果待核对 · 检查回执") }
            if (model.selected!!.optBoolean("managed")) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = model.draft, onValueChange = { model.editDraft(it) }, label = { Text("发送给 Claude") }, modifier = Modifier.weight(1f), maxLines = 5, enabled = model.pending == null)
                Button(onClick = { model.send() }, enabled = model.draft.isNotBlank() && !model.loadingSession && !model.running && !model.sending && model.pending == null && model.setup.optBoolean("ready") && model.run?.optString("status") != "acceptance_unknown") { Text("发送") }
            }
        }
    }
    if (model.creating) {
        var title by remember { mutableStateOf("") }; var cwd by remember { mutableStateOf("") }; var mode by remember { mutableStateOf("default") }
        AlertDialog(onDismissRequest = { if (!model.sending) model.creating = false }, title = { Text("新建 Claude 会话") }, text = {
            Column { OutlinedTextField(title, { title = it }, label = { Text("标题") }); OutlinedTextField(cwd, { cwd = it }, label = { Text("本机项目绝对路径") })
                listOf("default" to "按本机规则询问", "acceptEdits" to "自动批准文件编辑", "plan" to "计划模式").forEach { (value, label) -> Row { RadioButton(selected = mode == value, onClick = { mode = value }); Text(label) } }
                if (model.createError.isNotBlank()) Text(model.createError, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { model.create(cwd, title, mode) }, enabled = !model.sending && cwd.isNotBlank()) { Text("创建") } }, dismissButton = { TextButton(onClick = { model.creating = false }, enabled = !model.sending) { Text("取消") } })
    }
}
