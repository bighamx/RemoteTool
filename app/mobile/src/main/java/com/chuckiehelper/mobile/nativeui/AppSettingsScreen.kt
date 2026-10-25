@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

@Composable
internal fun AppSettingsScreen(
    updateSubtitle: String,
    onUpdate: () -> Unit,
    onDiagnostics: () -> Unit,
    onStartup: () -> Unit,
    onBack: () -> Unit,
    showUpdates: Boolean = true,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
                },
            )
        },
    ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(PaddingValues(16.dp)),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showUpdates) ToolRow("应用更新", updateSubtitle, Icons.Outlined.SystemUpdate, onUpdate)
                ToolRow("诊断日志", "查看与导出崩溃记录", Icons.Outlined.BugReport, onDiagnostics)
                ToolRow("开机自启", "恢复手机操控与转发通道", Icons.Outlined.PowerSettingsNew, onStartup)
                ToolRow("项目主页", "GitHub · RemoteTool", Icons.Outlined.OpenInNew) {
                    runCatching { uriHandler.openUri("https://github.com/bighamx/RemoteTool") }
                        .onFailure { Toast.makeText(context, "无法打开项目主页", Toast.LENGTH_SHORT).show() }
                }
            }
    }
}
