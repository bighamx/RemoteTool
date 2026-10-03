@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** Browse the remote device's filesystem, never Android's local storage. */
@Composable
fun PathPicker(
    api: NativeApi,
    title: String,
    initial: String,
    directoryOnly: Boolean = true,
    close: () -> Unit,
    select: (String) -> Unit,
) {
    var path by rememberSaveable { mutableStateOf(initial) }
    var data by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var manual by remember { mutableStateOf(false) }
    LaunchedEffect(api, path, refresh) {
        loading = true
        error = null
        try {
            data =
                api.json("/api/files/list" + (if (path.isBlank()) "" else "?path=${q(path)}"))
                    .array("data")
                    .objects()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "目录读取失败"
        } finally {
            loading = false
        }
    }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        BackHandler(enabled = path.isNotEmpty()) { path = parentPath(path) }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭") }
                    },
                    actions = {
                        IconButton(onClick = { manual = true }) {
                            Icon(Icons.Outlined.Edit, "手动输入路径")
                        }
                    },
                )
            },
            bottomBar = {
                if (directoryOnly)
                    Surface(tonalElevation = 3.dp) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                path.ifBlank { "请选择磁盘或目录" },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(
                                enabled = path.isNotBlank() && !loading && error == null,
                                onClick = { select(path) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("选择此目录")
                            }
                        }
                    }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    TextButton(enabled = path.isNotEmpty(), onClick = { path = parentPath(path) }) {
                        Icon(Icons.Outlined.ArrowUpward, null)
                        Text("上一级")
                    }
                    TextButton(onClick = { path = "" }) {
                        Icon(Icons.Outlined.Storage, null)
                        Text("所有磁盘")
                    }
                    IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "刷新") }
                }
                Text(
                    path.ifBlank { "服务器磁盘" },
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (loading || error != null) ErrorPane(error) { refresh++ }
                else
                    LazyColumn(
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val visible = data.filter { it.optBoolean("isDirectory") || !directoryOnly }
                        if (visible.isEmpty())
                            item {
                                Text(
                                    "此目录没有${if(directoryOnly)"子文件夹" else "文件"}",
                                    Modifier.padding(20.dp),
                                )
                            }
                        items(visible, key = { it.optString("path") }) { file ->
                            OutlinedCard(
                                Modifier.fillMaxWidth().clickable {
                                    if (file.optBoolean("isDirectory"))
                                        path = file.optString("path")
                                    else select(file.optString("path"))
                                }
                            ) {
                                Row(
                                    Modifier.padding(18.dp),
                                    verticalAlignment =
                                        androidx.compose.ui.Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (file.optBoolean("isDirectory")) Icons.Outlined.Folder
                                        else Icons.Outlined.Description,
                                        null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                    Text(
                                        file.optString("name"),
                                        Modifier.weight(1f).padding(horizontal = 12.dp),
                                    )
                                    Icon(Icons.Outlined.ChevronRight, null)
                                }
                            }
                        }
                    }
            }
        }
        if (manual)
            InputDialog("前往目录", "服务器上的目录路径", path, { manual = false }) {
                path = it
                manual = false
            }
    }
}
