package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

internal data class AgentApprovalChoice(val id: String, val label: String, val detail: String)

internal fun agentApprovalChoices(approval: JSONObject): List<AgentApprovalChoice> {
    val choices = approval.optJSONArray("choices")?.objects().orEmpty().mapNotNull {
        val id = it.optString("id"); val label = it.optString("label")
        if (id.isBlank() || label.isBlank()) null else AgentApprovalChoice(id, label, it.optString("detail"))
    }.distinctBy { it.id }
    return choices.ifEmpty { listOf(AgentApprovalChoice("once", "仅允许本次", ""), AgentApprovalChoice("deny", "拒绝", "")) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AgentApprovalButtons(approval: JSONObject, busy: Boolean, onApprove: (String) -> Unit) {
    var confirm by remember(approval.optString("request_id")) { mutableStateOf<AgentApprovalChoice?>(null) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        agentApprovalChoices(approval).forEach { choice ->
            if (choice.id == "deny") OutlinedButton(enabled = !busy, onClick = { onApprove(choice.id) }) { Text(choice.label) }
            else FilledTonalButton(enabled = !busy, onClick = {
                if (choice.id == "always" || choice.id.startsWith("network:")) confirm = choice else onApprove(choice.id)
            }) { Text(choice.label) }
        }
    }
    confirm?.let { choice ->
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(choice.label) },
            text = { Text(choice.detail + "\n\nCodex 将保存这条规则，用于之后匹配的请求。") },
            confirmButton = { TextButton(enabled = !busy, onClick = { confirm = null; onApprove(choice.id) }) { Text("保存规则并允许") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
}

@Composable
internal fun CodexSessionPermissionsDialog(api: NativeApi, session: String, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    var mode by remember(session) { mutableStateOf("default") }
    var loading by remember(session) { mutableStateOf(true) }
    var saving by remember(session) { mutableStateOf(false) }
    var error by remember(session) { mutableStateOf<String?>(null) }
    var loaded by remember(session) { mutableStateOf(false) }
    var currentInfo by remember(session) { mutableStateOf("") }
    var overrideMode by remember(session) { mutableStateOf("default") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api, session) {
        try {
            val result = api.json("/api/codex/sessions/${android.net.Uri.encode(session)}/permissions")
            overrideMode = result.optString("mode", "default")
            val actual = result.optJSONObject("current")
            val defaults = result.optJSONObject("configuration")
            currentInfo = when {
                actual != null -> "${actual.optString("description")}：\n${actual.optString("label")}"
                defaults != null -> "会话尚无可读取的实际权限记录。\n服务端配置默认：${defaults.optString("label")}"
                else -> "尚未获取到原生会话的实际权限，请更新服务端后重试。"
            }
            mode = overrideMode.takeUnless { it == "default" } ?: actual?.optString("mode")
                ?.takeIf { it in setOf("workspace", "full") } ?: "default"
            loaded = true
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = connectionFailureMessage(e) }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("当前会话权限") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (loaded) {
                Text(currentInfo, style = MaterialTheme.typography.bodySmall)
                Text("下一轮 RemoteTool 权限设置：" + when(overrideMode) {
                    "full" -> "完全访问 · 不再审批"
                    "workspace" -> "工作区访问 · 按需审批"
                    else -> "未覆盖，沿用 Codex 原有策略"
                }, style = MaterialTheme.typography.bodySmall)
            }
            listOf("workspace" to "工作区访问 · 按需审批", "full" to "完全访问 · 不再审批").forEach { (value, label) ->
                Row {
                    RadioButton(selected = mode == value, enabled = loaded && !saving, onClick = { mode = value })
                    TextButton(enabled = loaded && !saving, onClick = { mode = value }) { Text(label) }
                }
            }
            Text("完全访问允许联网、执行命令及读写工作区外文件，并跳过审批。", style = MaterialTheme.typography.bodySmall)
            Text("仅设置当前会话，从下一轮通过 RemoteTool 发送的消息开始生效；不会解除当前轮正在等待的审批。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = {
        TextButton(enabled = loaded && !saving && mode != "default", onClick = {
            saving = true; error = null
            scope.launch {
                try {
                    val result = api.json("/api/codex/sessions/${android.net.Uri.encode(session)}/permissions", obj("mode" to mode))
                    onSaved(result.getString("mode")); onDismiss()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = connectionFailureMessage(e) }
                finally { saving = false }
            }
        }) { Text(if (saving) "保存中" else "保存") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("取消") } })
}
