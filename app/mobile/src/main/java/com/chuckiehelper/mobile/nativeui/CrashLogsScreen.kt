@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.content.ClipData
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.chuckiehelper.mobile.diagnostics.CrashReports
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun CrashLogsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<File?>(null) }
    var content by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    fun action(block: suspend () -> Unit) { scope.launch {
        busy = true; error = null
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "操作失败" }
        finally { busy = false }
    } }
    LaunchedEffect(Unit) {
        try { reports = withContext(Dispatchers.IO) { CrashReports.files(context) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message }
    }
    fun back() { if (selected != null) { selected = null; content = "" } else onClose() }
    BackHandler { back() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("诊断日志") },
                navigationIcon = {
                    IconButton(onClick = { back() }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回")
                    }
                },
            )
        },
    ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    TextButton(enabled = !busy, onClick = { action { reports = withContext(Dispatchers.IO) { CrashReports.files(context) } } }) { Text("刷新") }
                    TextButton(enabled = !busy && reports.isNotEmpty(), onClick = { action {
                        val zip = withContext(Dispatchers.IO) { CrashReports.export(context) }
                        val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", zip)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newRawUri("诊断日志", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(intent, "导出诊断日志"))
                    } }) { Text("导出 ZIP") }
                    TextButton(enabled = !busy && reports.isNotEmpty(), onClick = { clearing = true }) { Text("清空") }
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (selected != null) {
                    Text(selected!!.name, style = MaterialTheme.typography.labelSmall)
                    SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(content, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                } else if (reports.isEmpty()) Text("暂无崩溃日志")
                else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(reports, key = { it.name }) { report ->
                        OutlinedCard(enabled = !busy, onClick = { action {
                            val text = withContext(Dispatchers.IO) { CrashReports.read(report) }
                            content = text; selected = report
                        } }) {
                            Text(report.name, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
    }
    if (clearing) AlertDialog(onDismissRequest = { clearing = false }, text = { Text("清空本机诊断日志？") },
        confirmButton = { TextButton(onClick = { clearing = false; action {
            withContext(Dispatchers.IO) { CrashReports.clear(context) }
            reports = emptyList(); selected = null; content = ""
        } }) { Text("清空") } }, dismissButton = { TextButton(onClick = { clearing = false }) { Text("取消") } })
}
