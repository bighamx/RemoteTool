@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun quotaWindow(usage: JSONObject?, minutes: Int): JSONObject? {
    val limits = mutableListOf<JSONObject>()
    usage?.optJSONObject("rateLimits")?.let { limits += it }
    usage?.optJSONObject("rateLimitsByLimitId")?.let { all ->
        all.keys().forEach { key -> all.optJSONObject(key)?.let { limits += it } }
    }
    return limits.flatMap { listOfNotNull(it.optJSONObject("primary"), it.optJSONObject("secondary")) }
        .firstOrNull { it.optInt("windowDurationMins") == minutes }
}

@Composable
fun CodexUsage(model: HermesModel, compact: Boolean, onClick: () -> Unit = {}) {
    CodexUsageValues(model.usage, compact, onClick)
}

@Composable
private fun CodexUsageValues(usage: JSONObject?, compact: Boolean, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 0.dp, vertical = if (compact) 3.dp else 6.dp)
        .clickable(enabled = compact, onClick = onClick), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf("5 小时" to 300, "周额度" to 10080).forEach { (label, duration) ->
            val window = quotaWindow(usage, duration)
            val used = window?.optDouble("usedPercent", -1.0) ?: -1.0
            val remaining = if (used >= 0 && used.isFinite()) (100 - used).coerceIn(0.0, 100.0) else null
            Column(Modifier.weight(1f)) {
                Text("$label · ${remaining?.let { "剩余 ${it.toInt()}%" } ?: "暂无数据"}", style = MaterialTheme.typography.labelSmall)
                if (remaining != null) LinearProgressIndicator(progress = { remaining.toFloat() / 100f },
                    modifier = Modifier.fillMaxWidth().padding(top = if (compact) 2.dp else 4.dp).height(if (compact) 2.dp else 3.dp),
                    color = if (remaining < 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    drawStopIndicator = {})
                if (!compact && window != null && window.optLong("resetsAt") > 0) {
                    val time = DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault())
                        .format(Instant.ofEpochSecond(window.getLong("resetsAt")))
                    Text("重置：$time", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun CodexAccountsDialog(model: HermesModel, close: () -> Unit, accountChanged: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<JSONObject?>(null) }
    val source = model.workspaces
    AlertDialog(
        onDismissRequest = close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("切换工作空间", Modifier.weight(1f))
                IconButton(onClick = { model.fetchWorkspaceUsages(true) }, enabled = model.workspaceUsageLoading.isEmpty() && !model.switchingAccount) { Icon(Icons.Outlined.Refresh, "刷新工作空间用量") }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前账户：" + model.accounts.optJSONObject("current_identity")?.optString("email").orEmpty().ifBlank { "尚未识别" }, style = MaterialTheme.typography.bodyMedium)
                Text("选择同一账户的个人／团队工作空间。切换会自动重启电脑 Codex，正在执行的任务会停止。", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(source.array("data").objects(), key = { it.getString("id") }) { account ->
                        val selected = source.optString("current") == account.getString("id")
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(account.optString("workspace_name"), style = MaterialTheme.typography.titleSmall)
                                    Text(account.optString("plan_type").replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall)
                                    if (account.optString("name") != account.optString("workspace_name")) Text(account.optString("name"), style = MaterialTheme.typography.labelSmall)
                                }
                                if (selected) Text("当前使用", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                else OutlinedButton(onClick = { model.switchAccount(account.getString("id"), true) { accountChanged(); close() } }, enabled = !model.switchingAccount) { Text("切换") }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true }, enabled = !model.switchingAccount) { Icon(Icons.Outlined.MoreVert, "管理工作空间") }
                                    DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("移除保存的登录") }, onClick = { menu = false; remove = account }) }
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 4.dp))
                            val quota = model.workspaceUsages[account.getString("id")]
                            if (quota?.optBoolean("available") == true) CodexUsageValues(quota.optJSONObject("usage"), compact = false)
                            else if (account.getString("id") in model.workspaceUsageLoading) {
                                Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("正在读取此工作空间用量…", style = MaterialTheme.typography.labelSmall)
                                }
                            } else Text(quota?.optString("error") ?: "用量尚未加载", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                TextButton(onClick = { model.importCurrentAccount() }) { Text("保存电脑当前登录") }
                if (model.loginInfo != null && !model.loginInfo!!.optBoolean("completed")) TextButton(onClick = { model.showLogin() }) { Text("查看登录验证码") }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { adding = true }, enabled = !model.switchingAccount) { Text("添加工作空间登录") } },
        dismissButton = { TextButton(onClick = close) { Text("关闭") } },
    )
    if (adding) InputDialog("添加工作空间登录", "备注名称", "工作空间", { adding = false }) { name ->
        model.beginAccountLogin(name, true) { model.showLogin() }
        adding = false
    }
    remove?.let { account ->
        ConfirmDialog("移除登录记录", "仅移除保存的登录记录。电脑当前 auth.json、配置和会话历史保持原样。", { remove = null }) {
            model.removeAccount(account.getString("id")); remove = null
        }
    }
}

@Composable
fun CodexSwitchingDialog(model: HermesModel) {
    if (!model.switchingAccount) return
    androidx.compose.ui.window.Dialog(onDismissRequest = {}, properties = androidx.compose.ui.window.DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator()
                Text("正在切换工作空间", style = MaterialTheme.typography.titleMedium)
                Text("正在重启 Codex、更新登录身份并恢复连接，请稍候。", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun CodexLoginDialog(model: HermesModel) {
    val info = model.loginInfo ?: return
    if (!model.loginVisible) return
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = { model.dismissLogin() },
        title = { Text(if (info.optBoolean("completed")) "登录结果" else "授权 Codex") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!info.optBoolean("completed")) {
                    Text("在官方登录页选择目标个人账户及个人／团队工作空间。电脑现有登录不会被覆盖。")
                    SelectionContainer { Text(info.optString("userCode"), style = MaterialTheme.typography.headlineSmall) }
                } else Text(if (info.optBoolean("success")) "登录记录已保存，可在账户与工作空间中切换。" else info.optString("error", "登录未完成"))
            }
        },
        confirmButton = {
            if (!info.optBoolean("completed")) TextButton(onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(info.getString("verificationUrl"))))
            }) { Text("打开官方登录页") }
        },
        dismissButton = { TextButton(onClick = { model.dismissLogin() }) { Text("关闭") } },
    )
}

@Composable
fun CodexQuestions(request: JSONObject, submit: (JSONObject) -> Unit) {
    var answers by remember(request.optString("request_id")) { mutableStateOf(mapOf<String, String>()) }
    val questions = request.array("questions").objects()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        questions.forEach { question ->
            val id = question.getString("id")
            Text(question.optString("question"), style = MaterialTheme.typography.titleSmall)
            question.array("options").objects().forEach { option ->
                Row(Modifier.fillMaxWidth().clickable { answers = answers + (id to option.optString("label")) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(answers[id] == option.optString("label"), { answers = answers + (id to option.optString("label")) })
                    Column(Modifier.weight(1f)) {
                        Text(option.optString("label"), style = MaterialTheme.typography.bodyMedium)
                        if (option.optString("description").isNotBlank()) Text(option.optString("description"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            OutlinedTextField(answers[id].orEmpty(), { answers = answers + (id to it) }, label = { Text(if (question.array("options").length() > 0) "或输入其他回答" else "你的回答") }, modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (question.optBoolean("isSecret")) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None)
        }
        Button(onClick = {
            val body = JSONObject()
            answers.forEach { (id, value) -> body.put(id, obj("answers" to org.json.JSONArray(listOf(value)))) }
            submit(body)
        }, enabled = questions.all { !answers[it.getString("id")].isNullOrBlank() }) { Text("发送回答") }
    }
}
