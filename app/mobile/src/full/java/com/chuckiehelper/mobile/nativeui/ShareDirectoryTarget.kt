package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ShareDirectoryTarget(inbox: ShareInboxModel, api: NativeApi, deviceId: String, modifier: Modifier = Modifier) {
    var path by rememberSaveable(deviceId) { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val busy = inbox.importing || inbox.uploading
    val result = inbox.directoryResult?.takeIf { it.deviceId == deviceId && directoryPositionKey(it.path) == directoryPositionKey(path) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("上传到设备文件夹", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(path, {}, readOnly = true, label = { Text("目标路径") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = { choosing = true }, enabled = !busy) { Text("选择文件夹") }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(inbox.files, key = { it.absolutePath }) { file ->
                Text(file.name, style = MaterialTheme.typography.bodyMedium)
            }
            if (inbox.files.isEmpty() && !inbox.importing) item { Text("本次分享没有文件，请选择 Codex 或 Hermes 发送文字") }
        }
        if (inbox.uploading) Text("正在上传 ${inbox.uploadedCount}/${inbox.files.size}…", style = MaterialTheme.typography.bodySmall)
        result?.let { Text("已上传 ${it.count} 个文件到 ${it.path}", color = MaterialTheme.colorScheme.primary) }
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = inbox::dismiss, enabled = !busy) { Text(if (result == null) "取消" else "完成") }
            if (result == null) Button(onClick = { inbox.uploadToDirectory(api, deviceId, path) },
                enabled = !busy && inbox.readable && inbox.files.isNotEmpty() && path.isNotBlank()) { Text("上传到此目录") }
        }
    }
    if (choosing) PathPicker(api, "选择上传目录", path, directoryOnly = true, allowCreateDirectory = true,
        close = { choosing = false }, select = { path = it; choosing = false; inbox.clearDirectoryConflicts() })
    val conflicts = inbox.conflictsForDirectory(deviceId, path)
    if (conflicts.isNotEmpty()) ConfirmDialog("覆盖同名文件", conflicts.joinToString("\n"),
        { inbox.clearDirectoryConflicts() }) {
        inbox.clearDirectoryConflicts()
        inbox.uploadToDirectory(api, deviceId, path, overwrite = true)
    }
}
