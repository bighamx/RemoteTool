package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

// Hermes memory_tool_store uses a section sign on its own line, not inline section signs.
private val entrySeparator = Regex("\\r?\\n[\\t ]*§[\\t ]*\\r?\\n")

internal fun isHermesEntryFile(id: String, content: String): Boolean =
    id == "memory" || id == "user" || entrySeparator.containsMatchIn(content)

@Composable
internal fun HermesMemoryEntries(content: String, onChange: (String) -> Unit, enabled: Boolean,
    textStyle: TextStyle, modifier: Modifier = Modifier) {
    val entries = remember(content) { if (content.isEmpty()) emptyList() else content.split(entrySeparator) }
    val delimiter = if (content.contains("\r\n")) "\r\n§\r\n" else "\n§\n"
    var editing by remember { mutableStateOf<Int?>(null) }
    var deleting by remember { mutableStateOf<Int?>(null) }
    val displayedEntries = if (entries.isEmpty() && editing == 0) listOf("") else entries
    fun change(index: Int, value: String) {
        onChange(if (entries.isEmpty()) value
            else entries.mapIndexed { i, entry -> if (i == index) value else entry }.joinToString(delimiter))
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("${entries.size} 条", style = MaterialTheme.typography.labelMedium)
                TextButton(enabled = enabled, onClick = {
                    val index = entries.size
                    onChange((entries + "").joinToString(delimiter))
                    editing = index
                }) { Text("添加条目") }
            }
        }
        itemsIndexed(displayedEntries) { index, entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", style = MaterialTheme.typography.labelMedium)
                        Row {
                            TextButton(enabled = enabled, onClick = { editing = if (editing == index) null else index }) {
                                Text(if (editing == index) "完成" else "编辑")
                            }
                            TextButton(enabled = enabled, onClick = { deleting = index }) {
                                Text("删除", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    if (editing == index) OutlinedTextField(entry, { change(index, it) },
                        Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 240.dp), enabled = enabled,
                        textStyle = textStyle, minLines = 4)
                    else Text(entry.ifBlank { "空条目" }, style = textStyle, modifier = Modifier.padding(bottom = 8.dp))
                }
            }
        }
    }
    deleting?.let { index ->
        AlertDialog(onDismissRequest = { deleting = null }, text = { Text("删除第 ${index + 1} 条？保存后生效。") },
            confirmButton = { TextButton(enabled = enabled, onClick = {
                onChange(entries.filterIndexed { i, _ -> i != index }.joinToString(delimiter))
                editing = null; deleting = null
            }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}
