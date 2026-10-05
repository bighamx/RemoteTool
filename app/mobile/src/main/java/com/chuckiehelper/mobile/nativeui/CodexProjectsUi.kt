@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable
fun CodexNewSessionDialog(api: NativeApi, model: HermesModel, close: () -> Unit, create: (JSONObject) -> Unit) {
    var mode by rememberSaveable { mutableStateOf("existing") }
    var selected by rememberSaveable { mutableStateOf("") }
    var directory by rememberSaveable { mutableStateOf("") }
    var newRoots by rememberSaveable { mutableStateOf(listOf<String>()) }
    var name by rememberSaveable { mutableStateOf("") }
    var search by rememberSaveable { mutableStateOf("") }
    var browse by remember { mutableStateOf(false) }
    val project = model.projects.firstOrNull { it.optString("id") == selected }
    var selectedRoot by rememberSaveable { mutableStateOf("") }
    val valid = when (mode) {
        "existing" -> project != null && selectedRoot.isNotBlank()
        "new" -> newRoots.isNotEmpty() && directory in newRoots && name.isNotBlank()
        else -> true
    }
    AlertDialog(
        onDismissRequest = { if (!model.submitting) close() },
        title = { Text("新建 Codex 会话") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("existing" to "已有项目", "new" to "新项目", "none" to "无项目").forEach { (id, label) ->
                        FilterChip(selected = mode == id, onClick = { mode = id; model.error = null }, label = { Text(label, maxLines = 1) }, enabled = !model.submitting, modifier = Modifier.weight(1f))
                    }
                }
                when (mode) {
                    "existing" -> {
                        OutlinedTextField(search, { search = it }, label = { Text("搜索电脑上的项目") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        if (model.projectsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val rows = model.projects.filter { it.optString("name").contains(search, true) || it.array("roots").objects().any { root -> root.optString("path").contains(search, true) } }
                            if (rows.isEmpty() && !model.projectsLoading) item { Text("没有匹配项目，可选择“新项目”或“无项目”。", style = MaterialTheme.typography.bodySmall) }
                            items(rows, key = { it.getString("id") }) { item ->
                                OutlinedCard(Modifier.fillMaxWidth().clickable(enabled = !model.submitting) { selected = item.getString("id"); selectedRoot = item.array("roots").objects().firstOrNull()?.optString("path").orEmpty() }) {
                                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(if (selected == item.getString("id")) Icons.Outlined.RadioButtonChecked else Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                            Text(item.optString("name"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(item.array("roots").objects().joinToString("\n") { it.optString("path") }, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        val roots = project?.array("roots")?.objects().orEmpty()
                        if (roots.size > 1) {
                            Text("工作目录", style = MaterialTheme.typography.labelMedium)
                            roots.forEach { root ->
                                val path = root.optString("path")
                                Row(Modifier.fillMaxWidth().clickable { selectedRoot = path }, verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selectedRoot == path, { selectedRoot = path })
                                    Text(path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                    "new" -> {
                        OutlinedTextField(name, { name = it }, label = { Text("项目名称") }, singleLine = true, enabled = !model.submitting, modifier = Modifier.fillMaxWidth())
                        Text("项目文件夹 · 单选项指定默认工作目录", style = MaterialTheme.typography.labelMedium)
                        LazyColumn(Modifier.heightIn(max = 220.dp)) {
                            items(newRoots, key = { it }) { path ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(directory == path, { directory = path }, enabled = !model.submitting)
                                    Text(path, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    IconButton(onClick = { newRoots = newRoots - path; if (directory == path) directory = newRoots.firstOrNull().orEmpty() }, enabled = !model.submitting) { Icon(Icons.Outlined.Close, "移除文件夹") }
                                }
                            }
                        }
                        OutlinedButton(onClick = { browse = true }, enabled = !model.submitting && newRoots.size < 16, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.CreateNewFolder, null); Spacer(Modifier.width(8.dp)); Text("添加文件夹")
                        }
                        Text("可选择多个现有目录，也可在选择器中创建文件夹。", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Text("独立会话，不归入项目。使用独立工作目录保存本次会话的文件。", style = MaterialTheme.typography.bodyMedium)
                }
                model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (model.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(enabled = valid && !model.submitting && !model.projectsLoading, onClick = {
            create(when (mode) {
                "existing" -> obj("project_mode" to mode, "project_id" to selected, "cwd" to selectedRoot)
                "new" -> obj("project_mode" to mode, "project_name" to name.trim(), "cwd" to directory, "roots" to org.json.JSONArray(newRoots.map { obj("path" to it) }))
                else -> obj("project_mode" to "none")
            })
        }) { Text(if (model.submitting) "正在创建…" else "新建会话") } },
        dismissButton = { TextButton(onClick = close, enabled = !model.submitting) { Text("取消") } },
    )
    if (browse) PathPicker(api, "选择项目工作目录", directory, allowCreateDirectory = true, close = { browse = false }) { path ->
        if (path !in newRoots) newRoots = newRoots + path
        if (directory.isBlank()) directory = path
        if (name.isBlank()) name = path.trimEnd('/', '\\').substringAfterLast('\\').substringAfterLast('/')
        browse = false
    }
}
