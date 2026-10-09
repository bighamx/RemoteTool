package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*

@Composable
fun FileGitDialog(api: NativeApi, metadata: String, action: String, close: () -> Unit, changed: () -> Unit) {
    val title = when (action) { "status" -> "Git 状态"; "commit-push" -> "Git 提交并推送"; "pull" -> "Git 拉取"; else -> "Git 推送" }
    var message by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun execute() {
        if (running) return
        running = true; error = null; output = null
        scope.launch {
            try {
                val job = api.json("/api/files/git", obj("path" to metadata, "action" to action, "message" to message)).getString("jobId")
                while (true) {
                    val status = api.json("/api/files/git/$job")
                    if (status.optBoolean("done")) {
                        val result = status.getJSONObject("result")
                        output = result.optString("output").ifBlank { if (result.optBoolean("success")) "操作完成" else "" }
                        if (!result.optBoolean("success")) error = result.optString("error", "Git 操作失败")
                        if (action != "status") changed()
                        break
                    }
                    delay(1000)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "${e.message ?: "连接失败"}\n请先查看仓库状态，不要直接重复提交。" }
            finally { running = false }
        }
    }
    LaunchedEffect(Unit) { if (action == "status") execute() }
    AlertDialog(
        onDismissRequest = { if (!running) close() },
        properties = DialogProperties(dismissOnBackPress = !running, dismissOnClickOutside = !running),
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(parentPath(metadata), style = MaterialTheme.typography.bodySmall)
                if (action == "commit-push" && output == null) {
                    Text("将暂存仓库中的全部改动（包含新增、修改和删除），创建提交后推送当前分支。")
                    OutlinedTextField(message, { message = it }, enabled = !running, label = { Text("提交说明") }, modifier = Modifier.fillMaxWidth())
                }
                if (action == "pull") Text("拉取当前分支的上游，只允许快进；分支分叉时会提示失败。")
                if (running) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在执行，请稍候…") }
                output?.takeIf { it.isNotBlank() }?.let { SelectionContainer { Text(it, style = MaterialTheme.typography.bodySmall) } }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (output == null && error == null || action == "status") TextButton(onClick = { execute() },
                enabled = !running && (action != "commit-push" || message.isNotBlank() && message.length <= 2000)) {
                Text(if (action == "status") "刷新" else "执行")
            }
        },
        dismissButton = { TextButton(onClick = close, enabled = !running) { Text("关闭") } },
    )
}
