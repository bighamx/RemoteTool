@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun TextDocument(
    title: String,
    initial: String,
    editable: Boolean,
    close: () -> Unit,
    save: (String) -> Unit,
) {
    fun plain(value: String) = value.replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]"), "")
    var text by remember { mutableStateOf(if (editable) initial else plain(initial)) }
    var discard by remember { mutableStateOf(false) }
    LaunchedEffect(initial) { if (!editable) text = plain(initial) }
    fun exit() {
        if (editable && text != initial) discard = true else close()
    }
    Dialog(
        onDismissRequest = { exit() },
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { exit() }) { Icon(Icons.Outlined.Close, "关闭") }
                    },
                    actions = { if (editable) TextButton(onClick = { save(text) }) { Text("保存") } },
                )
            }
        ) { padding ->
            OutlinedTextField(
                text,
                { if (editable) text = it },
                readOnly = !editable,
                modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
                textStyle =
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        if (discard)
            ConfirmDialog("放弃修改", "未保存的修改将丢失。", { discard = false }) {
                discard = false
                close()
            }
    }
}

@Composable
fun InputDialog(
    title: String,
    label: String,
    initial: String = "",
    close: () -> Unit,
    submit: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value,
                { value = it },
                label = { Text(label) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = value.isNotBlank(), onClick = { submit(value) }) { Text("确认") }
        },
        dismissButton = { TextButton(onClick = close) { Text("取消") } },
    )
}
