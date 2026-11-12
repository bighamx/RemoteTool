package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ClaudeNewSessionDialog(api: NativeApi, model: HermesModel, close: () -> Unit, onCreated: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var cwd by remember { mutableStateOf(model.sessions.firstOrNull { it.optString("id") == model.selectedId }?.cleanSetting("cwd").orEmpty()) }
    var mode by remember { mutableStateOf("default") }
    val choices = listOf("default" to "按本机规则询问", "acceptEdits" to "自动批准文件编辑", "plan" to "计划模式")
    var browsing by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("新建 Claude Code 会话") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("标题（可选）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cwd, { cwd = it }, label = { Text("本机工作目录") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = !busy, onClick = { browsing = true }) { Text("浏览远程目录") }
            choices.forEach { choice ->
                Row { RadioButton(mode == choice.first, enabled = !busy, onClick = { mode = choice.first })
                    TextButton(enabled = !busy, onClick = { mode = choice.first }) { Text(choice.second) } }
            }
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(enabled = !busy && cwd.isNotBlank(), onClick = {
        scope.launch { busy = true; try { model.newSession(title, { close(); onCreated() }, obj("cwd" to cwd.trim(), "permission_mode" to mode)).join() } finally { busy = false } }
    }) { Text(if (busy) "创建中…" else "创建") } }, dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("取消") } })
    if (browsing) PathPicker(api, "Claude 工作目录", cwd, close = { browsing = false }, select = { cwd = it; browsing = false })
}
