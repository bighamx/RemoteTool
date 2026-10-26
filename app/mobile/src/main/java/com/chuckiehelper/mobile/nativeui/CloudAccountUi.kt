@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun CloudAccountEntry(model: CloudAccountModel) {
    ToolRow("云账户", model.subtitle, Icons.Outlined.Cloud) { model.open() }
}

@Composable
internal fun CloudAccountHost(model: CloudAccountModel, appearance: AppAppearance, onAppearanceChange: (AppAppearance) -> Unit) {
    if (!model.panelVisible) return
    val context = LocalContext.current
    val scan = rememberCloudQrScanner(model)
    var confirmUpload by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var chooseAppearance by remember { mutableStateOf(false) }
    val working = model.busy || model.qr.busy
    val actionShape = RoundedCornerShape(12.dp)
    val failure = model.qr.error ?: model.error
    val status = failure ?: model.notice ?: model.qr.notice
    ModalBottomSheet(onDismissRequest = { model.closePanel() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
            .padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("账户与同步", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { model.closePanel() }) { Text("关闭") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Person, null, Modifier.size(26.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(if (model.loggedIn) model.displayName else "未登录", style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val caption = if (!model.loggedIn) "登录后同步设备与设置" else model.cloudInfo?.let { info ->
                        if (info.isNull("payload")) "暂无云端配置" else "云端更新于 ${displayTime(info.optString("updatedAt"))}"
                    } ?: "设备与设置 · 手动同步"
                    Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (model.loggedIn) TextButton(onClick = { confirmLogout = true }, enabled = !working,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("退出") }
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
            }
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            if (!model.loggedIn) {
                Button(onClick = { model.beginLogin(context) }, enabled = !working, shape = actionShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (model.waitingForLogin) "继续登录" else if (model.qr.loginEnabled) "浏览器登录" else "登录")
                }
                if (model.qr.loginEnabled) Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scan(false) }, enabled = !working, shape = actionShape,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("扫码登录") }
                    OutlinedButton(onClick = { model.createQr() }, enabled = !working, shape = actionShape,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("显示二维码") }
                }
                if (model.waitingForLogin) TextButton(onClick = { model.cancelLogin() }, enabled = !working) { Text("取消登录") }
            } else {
                Button(onClick = { model.refresh() }, enabled = !working, shape = actionShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("刷新云端状态")
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { model.download() }, enabled = !working, shape = actionShape,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("下载云端配置") }
                    OutlinedButton(onClick = { confirmUpload = true }, enabled = !working && model.cloudInfo != null, shape = actionShape,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("上传本地配置") }
                }
                if (model.qr.approvalEnabled) {
                    OutlinedButton(enabled = !working, shape = actionShape, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), onClick = {
                        if (model.hasQrApprovalScope && !model.qr.needsReauthorization) scan(true) else model.beginLogin(context,requestApproval = true)
                    }) { Text(if (model.hasQrApprovalScope && !model.qr.needsReauthorization) "扫一扫授权登录" else "启用扫码授权") }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.loggedIn) TextButton(modifier = Modifier.weight(1f), onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${CloudAccountModel.BASE}/AppData/Index?appId=${CloudAccountModel.APP_ID}"))) }) {
                    Text("管理云端配置")
                }
                TextButton(modifier = Modifier.weight(1f), onClick = { chooseAppearance = true }) {
                    Text("外观 · ${appearance.label}")
                }
            }
        }
    }
    CloudQrHost(model)
    if (chooseAppearance) AlertDialog(onDismissRequest = { chooseAppearance = false }, title = { Text("外观") },
        text = { Column {
            AppAppearance.entries.forEach { option ->
                Row(Modifier.fillMaxWidth().selectable(selected = appearance == option, role = Role.RadioButton, onClick = {
                    onAppearanceChange(option); chooseAppearance = false
                }).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(selected = appearance == option, onClick = null)
                    Text(option.label)
                }
            }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { chooseAppearance = false }) { Text("取消") } })
    if (confirmUpload && model.loggedIn) AlertDialog(onDismissRequest = { confirmUpload = false },
        title = { Text("上传配置") }, text = { Text("上传设备、用户名密码和设置，覆盖云端配置。登录令牌和聊天记录不上传。") },
        confirmButton = { TextButton(onClick = { confirmUpload = false; model.upload() }) { Text("确认上传") } },
        dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("取消") } })
    model.downloadPreview?.let { payload ->
        AlertDialog(onDismissRequest = { model.dismissPreview() }, title = { Text("应用云端配置") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${payload.getJSONArray("devices").length()} 台设备 · ${displayTime(payload.optString("exportedAt"))}")
                val credentialCount = payload.getJSONArray("devices").objects().count { it.has("credentials") }
                Text(if (credentialCount > 0) "更新设置和 $credentialCount 台设备的用户名密码，原配置自动备份。" else "合并设备并更新本机设置，原配置自动备份。")
            } },
            confirmButton = { TextButton(enabled = !model.busy, onClick = { model.applyDownload() }) { Text("应用") } },
            dismissButton = { TextButton(enabled = !model.busy, onClick = { model.dismissPreview() }) { Text("取消") } })
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出云账户") },
        text = { Text("退出后保留本机设备和设置。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; model.logout() }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}
