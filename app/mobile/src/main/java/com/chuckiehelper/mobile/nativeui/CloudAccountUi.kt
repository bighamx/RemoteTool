@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun CloudAccountEntry(model: CloudAccountModel) {
    ToolRow("云账户", model.subtitle, Icons.Outlined.Cloud) { model.open() }
}

@Composable
internal fun CloudAccountHost(model: CloudAccountModel) {
    if (!model.panelVisible) return
    val context = LocalContext.current
    var confirmUpload by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = { model.panelVisible = false },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)
            .padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row {
                Text("云账户", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onClick = { model.panelVisible = false }) { Icon(Icons.Outlined.Close, "关闭") }
            }
            Text("api.xbyham.com", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (model.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            model.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            if (!model.loggedIn) {
                Button(onClick = { model.beginLogin(context) }, enabled = !model.busy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (model.waitingForLogin) "继续登录" else "登录")
                }
                if (model.waitingForLogin) TextButton(onClick = { model.cancelLogin() }, enabled = !model.busy) { Text("取消") }
            } else {
                Text(model.displayName, style = MaterialTheme.typography.titleLarge)
                model.cloudInfo?.let { info ->
                    Text(if (info.isNull("payload")) "暂无云端配置" else "更新于 ${displayTime(info.optString("updatedAt"))}",
                        style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { confirmUpload = true }, enabled = !model.busy && model.cloudInfo != null, modifier = Modifier.weight(1f)) { Text("上传配置") }
                    OutlinedButton(onClick = { model.download() }, enabled = !model.busy, modifier = Modifier.weight(1f)) { Text("下载配置") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { model.refresh() }, enabled = !model.busy) { Text("刷新") }
                    TextButton(onClick = { confirmLogout = true }, enabled = !model.busy) { Text("退出登录") }
                }
            }
            if (model.loggedIn) TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("${CloudAccountModel.BASE}/AppData/Index?appId=${CloudAccountModel.APP_ID}"))) }) {
                Text("管理云端配置")
            }
        }
    }
    if (confirmUpload && model.loggedIn) AlertDialog(onDismissRequest = { confirmUpload = false },
        title = { Text("上传配置") }, text = { Text("将设备和设置保存到云端，覆盖已有配置。密码和聊天记录不上传。") },
        confirmButton = { TextButton(onClick = { confirmUpload = false; model.upload() }) { Text("确认上传") } },
        dismissButton = { TextButton(onClick = { confirmUpload = false }) { Text("取消") } })
    model.downloadPreview?.let { payload ->
        AlertDialog(onDismissRequest = { model.dismissPreview() }, title = { Text("应用云端配置") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("${payload.getJSONArray("devices").length()} 台设备 · ${displayTime(payload.optString("exportedAt"))}")
                Text("合并设备并更新本机设置，原配置将自动备份。")
            } },
            confirmButton = { TextButton(enabled = !model.busy, onClick = { model.applyDownload() }) { Text("应用") } },
            dismissButton = { TextButton(enabled = !model.busy, onClick = { model.dismissPreview() }) { Text("取消") } })
    }
    if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("退出云账户") },
        text = { Text("退出后保留本机设备和设置。") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; model.logout() }) { Text("退出") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } })
}
