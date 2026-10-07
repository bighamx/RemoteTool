package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
internal fun CodexTitleModelDialog(model: HermesModel, onClose: () -> Unit) {
    var enabled by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("chat_completions") }
    var clearKey by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf("") }
    var initialized by remember { mutableStateOf(false) }
    var choicesOpen by remember { mutableStateOf(false) }
    LaunchedEffect(model.titleModel) {
        val settings = model.titleModel ?: return@LaunchedEffect
        if (!initialized) {
            enabled = settings.optBoolean("enabled"); url = settings.optString("base_url")
            name = settings.optString("model"); mode = settings.optString("api_mode", "chat_completions")
            prompt = settings.optString("prompt"); initialized = true
        }
    }
    fun body() = JSONObject().put("enabled", enabled).put("base_url", url.trim()).put("model", name.trim())
        .put("api_mode", mode).put("api_key", key).put("clear_api_key", clearKey).put("prompt", prompt)
    LaunchedEffect(url, key, mode, clearKey, initialized) {
        model.invalidateTitleModels()
        if (!initialized || !url.trim().startsWith("http")) return@LaunchedEffect
        delay(800)
        model.fetchTitleModels(body())
    }
    fun save(test: Boolean) { model.saveTitleModel(body(), test) { key = ""; clearKey = false } }
    Dialog(onDismissRequest = { if (!model.titleModelBusy) onClose() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.9f).imePadding()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("标题生成模型", style = MaterialTheme.typography.titleLarge)
                Text("Codex 与 Hermes 共用此配置，独立于会话主模型。", style = MaterialTheme.typography.bodySmall)
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!initialized) Text("正在读取配置…")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("自动生成标题"); Switch(enabled, { enabled = it }, enabled = initialized && !model.titleModelBusy)
                    }
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("API URL") }, placeholder = { Text("https://example.com/v1") }, singleLine = true, enabled = initialized && !model.titleModelBusy)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(mode == "chat_completions", { mode = "chat_completions" }, label = { Text("Chat Completions") }, enabled = !model.titleModelBusy)
                        FilterChip(mode == "responses", { mode = "responses" }, label = { Text("Responses") }, enabled = !model.titleModelBusy)
                    }
                    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (model.titleModel?.optBoolean("has_api_key") == true) "Key（已保存，留空保留）" else "API Key（可选）") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), enabled = initialized && !model.titleModelBusy)
                    if (model.titleModel?.optBoolean("has_api_key") == true) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(clearKey, { clearKey = it }, enabled = !model.titleModelBusy); Text("清除已保存 Key")
                    }
                    Box {
                        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("廉价模型名称") }, singleLine = true, enabled = initialized && !model.titleModelBusy,
                            trailingIcon = { IconButton(onClick = { choicesOpen = true }, enabled = model.titleModelChoices.isNotEmpty()) { Icon(Icons.Outlined.ArrowDropDown, "选择模型") } })
                        DropdownMenu(choicesOpen, { choicesOpen = false }, Modifier.heightIn(max = 240.dp)) {
                            model.titleModelChoices.filter { name.isBlank() || it.contains(name, ignoreCase = true) }.ifEmpty { model.titleModelChoices }.forEach { choice ->
                                DropdownMenuItem(text = { Text(choice) }, onClick = { name = choice; choicesOpen = false })
                            }
                        }
                    }
                    Text(if (model.titleModelsLoading) "正在获取模型列表…" else model.titleModelsMessage ?: "填写 URL 和 Key 后自动获取模型，也可手动填写", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.fetchTitleModels(body()) }, enabled = initialized && !model.titleModelsLoading && !model.titleModelBusy && url.isNotBlank()) { Text("刷新模型列表") }
                    OutlinedTextField(prompt, { if (it.length <= 2000) prompt = it }, Modifier.fillMaxWidth(), label = { Text("标题提示词") }, minLines = 3, maxLines = 6, supportingText = { Text("${prompt.length}/2000，首条用户消息另行传入") }, enabled = initialized && !model.titleModelBusy)
                    TextButton(onClick = { prompt = model.titleModel?.optString("default_prompt").orEmpty() }, enabled = initialized && !model.titleModelBusy) { Text("恢复默认提示词") }
                }
                // Keep feedback outside the scrolling fields so results are always visible.
                if (model.titleModelBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                model.titleModelMessage?.let { message ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) { Text(message, Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onClose, enabled = !model.titleModelBusy) { Text("关闭") }
                    TextButton(onClick = { save(false) }, enabled = initialized && !model.titleModelBusy) { Text("保存") }
                    Button(onClick = { save(true) }, enabled = initialized && !model.titleModelBusy && url.isNotBlank() && name.isNotBlank()) { Text("保存并测试") }
                }
            }
        }
    }
}
