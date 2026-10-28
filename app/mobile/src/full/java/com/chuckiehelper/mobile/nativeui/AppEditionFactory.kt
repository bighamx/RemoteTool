@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import com.chuckiehelper.mobile.R
import com.chuckiehelper.mobile.RemoteActivity
import androidx.compose.ui.unit.dp

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.saveable.SaveableStateHolder

internal fun receiveEditionIntent(activity: ComponentActivity, intent: Intent) {
    ViewModelProvider(activity)[ShareInboxModel::class.java].receive(intent)
}

@Composable internal fun rememberAppEdition(): AppEdition {
    val shares: ShareInboxModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val updates: AppUpdateModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val screens = rememberSaveableStateHolder()
    return remember(shares, updates, screens) { FullEdition(shares, updates, screens) }
}

private class FullEdition(
    private val shares: ShareInboxModel,
    private val updates: AppUpdateModel,
    private val screens: SaveableStateHolder,
) : AppEdition() {
    override val initialRoute = "系统"
    override val showUpdates = true
    override val updateSubtitle get() = updates.subtitle
    private var composeTarget by mutableStateOf<String?>(null)
    override fun reloadSettings() = updates.reloadCloudSettings()
    override fun openUpdates() = updates.open()
    override fun normalizeRoute(route: String) = if (route == "文件") "Codex" else route
    override fun ownsToolbar(route: String) = route in listOf("Codex", "Hermes", "文件", "Cloudflare")
    private fun draft(deviceId: String?) = shares.ready?.takeIf { it.deviceId == deviceId }
    override fun shareId(deviceId: String?) = draft(deviceId)?.id
    override fun shareRoute(deviceId: String?) = draft(deviceId)?.let { if (it.agent == "codex") "Codex" else "Hermes" }
    override fun choosingShare(deviceId: String?) = shares.pending && draft(deviceId) == null
    @Composable override fun UpdateHost() { AppUpdateHost(updates) }
    @Composable override fun ShareChooser(api: NativeApi, deviceId: String, deviceName: String, changeDevice: () -> Unit) {
        ShareTargetScreen(shares, api, deviceId, deviceName, changeDevice)
    }
    @Composable override fun Navigation(route: String, deviceName: String, onRoute: (String) -> Unit) {
        NavigationBar(modifier = Modifier.selectableGroup()) {
            listOf(
                    "系统" to Icons.Outlined.Speed,
                    "远程" to Icons.Outlined.DesktopWindows,
                    "Codex" to Icons.Outlined.Code,
                    "Hermes" to Icons.Outlined.SmartToy,
                    "更多" to Icons.Outlined.MoreHoriz,
                )
                .forEach { (name, icon) ->
                    val selected = route == name
                    val color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp)
                            .height(68.dp).clip(RoundedCornerShape(20.dp))
                            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .selectable(selected, role = Role.Tab, onClick = { onRoute(name) }),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        CompositionLocalProvider(LocalContentColor provides color) { when (name) {
                            "Hermes" -> HermesOfficialIcon(Modifier.size(40.dp))
                            "Codex" -> Icon(painterResource(R.drawable.ic_codex_official), name, Modifier.size(40.dp))
                            else -> Icon(icon, name)
                        } }
                        if (name !in listOf("Hermes", "Codex")) {
                            Spacer(Modifier.height(4.dp))
                            Text(if (name == "系统" && !selected) deviceName else name,
                                color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
        }
    }
    @Composable override fun MoreItems(onDetail: (String) -> Unit) {
        ToolRow("Cloudflare", "DNS · R2 · D1 · Tunnel", icon = {
            androidx.compose.foundation.Image(painterResource(R.drawable.ic_cloudflare), null, Modifier.size(24.dp))
        }) { onDetail("Cloudflare") }
        ToolRow("文件管理", "浏览 · 传输 · 图片与视频预览", Icons.Outlined.Folder) { onDetail("文件") }
        ToolRow(
            "Docker",
            "容器 · Compose · 镜像管理",
            Icons.Outlined.Layers,
        ) {
            onDetail("Docker")
        }
        ToolRow(
            "命令终端",
            "Shell · PowerShell · CMD",
            Icons.Outlined.Terminal,
        ) {
            onDetail("终端")
        }
        ToolRow(
            "Hangfire",
            "任务、队列与执行历史",
            Icons.Outlined.Schedule,
        ) {
            onDetail("Hangfire")
        }
        ToolRow("进程管理", "查看资源占用与结束进程", Icons.Outlined.Memory) {
            onDetail("进程")
        }

    }
    @Composable override fun Page(route: String, api: NativeApi, deviceId: String, error: (String) -> Unit,
        onDetail: (String) -> Unit, onClose: () -> Unit): Boolean {
        val sharedDraft = draft(deviceId)
        when (route) {
            "Cloudflare" -> CloudflareScreen(api, error, onClose)
            "系统" -> SystemScreen(api, onProcesses = { onDetail("进程") }, onTerminal = { onDetail("终端") }, onError = error)
            "进程" -> ProcessScreen(api, error)
            "远程" -> RemoteScreen(api)
            "文件" -> screens.SaveableStateProvider("files-$deviceId") {
                FilesScreen(api, error, onClose = onClose) { path -> composeTarget = path; onDetail("Compose 管理") }
            }
            "Docker" -> DockerScreen(api, error)
            "Compose 管理" -> ComposeScreen(api, error, composeTarget)
            "终端" -> TerminalScreen(api, error)
            "Hangfire" -> JobsScreen(api, error)
            "Hermes" -> screens.SaveableStateProvider("hermes-$deviceId") {
                HermesScreen(api, deviceId, incoming = sharedDraft?.takeIf { it.agent == "hermes" }, onSharedHandled = { shares.finishDelivery(it) })
            }
            "Codex" -> screens.SaveableStateProvider("codex-$deviceId") {
                HermesScreen(api, deviceId, "codex", incoming = sharedDraft?.takeIf { it.agent == "codex" }, onSharedHandled = { shares.finishDelivery(it) })
            }
            else -> return false
        }
        return true
    }
}

@Composable
private fun RemoteScreen(api: NativeApi) {
    val context = LocalContext.current
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Icon(
            Icons.Outlined.DesktopWindows,
            null,
            Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("远程桌面", style = MaterialTheme.typography.headlineMedium)
        Text(
            "H.264 硬件解码 · 全屏控制\n直接触控与触控板 · 手机输入法\n双指缩放，放大后拖动画面",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = {
                context.startActivity(
                    Intent(context, RemoteActivity::class.java).putExtra("endpoint", api.base).putExtra("deviceCookie", api.cookie())
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("连接远程桌面")
        }
        Text("点击连接后才启动视频和键鼠通道。", style = MaterialTheme.typography.bodySmall)
    }
}
