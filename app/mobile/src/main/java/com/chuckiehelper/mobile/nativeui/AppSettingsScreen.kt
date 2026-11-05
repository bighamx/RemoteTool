@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.Switch
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
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
    appearance: AppAppearance,
    onAppearanceChange: (AppAppearance) -> Unit,
    showUpdates: Boolean = true,
    onStorage: (() -> Unit)? = null,
    predictiveBack: Boolean = false,
    onPredictiveBackChange: (Boolean) -> Unit = {},
    usageStats: Boolean = true,
    onUsageStatsChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var chooseAppearance by rememberSaveable { mutableStateOf(false) }
    SceneBackHandler(onBack = onBack)
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
                ToolRow("外观", appearance.label, Icons.Outlined.Palette) { chooseAppearance = true }
                if (showUpdates) ToolRow("应用更新", updateSubtitle, Icons.Outlined.SystemUpdate, onUpdate)
                onStorage?.let { ToolRow("空间管理", "会话与附件 · 本机和服务端", androidx.compose.material.icons.Icons.Outlined.Storage, it) }
                ToolRow("诊断日志", "查看与导出崩溃记录", Icons.Outlined.BugReport, onDiagnostics)
                ToolRow("开机自启", "恢复手机操控与转发通道", Icons.Outlined.PowerSettingsNew, onStartup)
                ToolRow("项目主页", "GitHub · RemoteTool", Icons.Outlined.OpenInNew) {
                    runCatching { uriHandler.openUri("https://github.com/bighamx/RemoteTool") }
                        .onFailure { Toast.makeText(context, "无法打开项目主页", Toast.LENGTH_SHORT).show() }
                }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().toggleable(value = predictiveBack, role = Role.Switch,
                        onValueChange = onPredictiveBackChange).padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("预测性返回手势", style = MaterialTheme.typography.titleMedium)
                            Text("滑动预览上一级页面，撤回手势可取消返回", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = predictiveBack, onCheckedChange = null)
                    }
                }
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().toggleable(value = usageStats, role = Role.Switch,
                        onValueChange = onUsageStatsChange).padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("使用统计", style = MaterialTheme.typography.titleMedium)
                            Text("上报随机安装标识、设备型号、版本及活跃次数，不含会话内容", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = usageStats, onCheckedChange = null)
                    }
                }
            }
    }
    if (chooseAppearance) AlertDialog(
        onDismissRequest = { chooseAppearance = false },
        title = { Text("外观") },
        text = {
            Column {
                AppAppearance.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = appearance == option,
                            role = Role.RadioButton,
                            onClick = { onAppearanceChange(option); chooseAppearance = false },
                        ).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RadioButton(selected = appearance == option, onClick = null)
                        Text(option.label)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { chooseAppearance = false }) { Text("取消") } },
    )
}
