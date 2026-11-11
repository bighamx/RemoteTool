@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.json.JSONArray

internal data class MobileDestination(val route: String, val label: String, val description: String, val icon: ImageVector)
internal val mobileDestinations = listOf(
    MobileDestination("系统", "系统", "设备状态与资源", Icons.Outlined.Speed),
    MobileDestination("远程", "远程", "远程桌面", Icons.Outlined.DesktopWindows),
    MobileDestination("Codex", "Codex", "Codex 会话", Icons.Outlined.Code),
    MobileDestination("Claude Code", "Claude", "Claude Code 会话", Icons.Outlined.Code),
    MobileDestination("Hermes", "Hermes", "Hermes 会话", Icons.Outlined.SmartToy),
    MobileDestination("文件", "文件", "浏览 · 传输 · 图片与视频预览", Icons.Outlined.Folder),
    MobileDestination("空间管理", "空间管理", "本机缓存 · 会话附件 · 服务端回收站", Icons.Outlined.Storage),
    MobileDestination("Cloudflare", "Cloudflare", "DNS · R2 · D1 · Tunnel", Icons.Outlined.Cloud),
    MobileDestination("Docker", "Docker", "容器 · Compose · 镜像管理", Icons.Outlined.Layers),
    MobileDestination("终端", "终端", "Shell · PowerShell · CMD", Icons.Outlined.Terminal),
    MobileDestination("Hangfire", "任务", "任务、队列与执行历史", Icons.Outlined.Schedule),
    MobileDestination("进程", "进程", "查看资源占用与结束进程", Icons.Outlined.Memory),
    MobileDestination("端口转发", "端口转发", "TCP / UDP · 双向局域网访问", Icons.Outlined.SwapHoriz),
)
internal val defaultMobileNavigation = listOf("系统", "远程", "Codex", "Claude Code", "Hermes")
internal fun normalizeMobileNavigation(value: List<String>?): List<String> {
    val available = mobileDestinations.map { it.route }.toSet()
    return ((value ?: defaultMobileNavigation) + defaultMobileNavigation + available).filter { it in available }.distinct()
}
internal fun moveMobileDestination(order: List<String>, from: Int, to: Int): List<String> =
    if (from !in order.indices || to !in order.indices) order else order.toMutableList().apply { add(to, removeAt(from)) }

internal class MobileNavigationSettings(private val preferences: SharedPreferences) {
    var order by mutableStateOf(read()); private set
    private fun read() = normalizeMobileNavigation(runCatching {
        val rows = JSONArray(preferences.getString("order", "null"))
        (0 until rows.length()).mapNotNull { rows.opt(it) as? String }
    }.getOrNull())
    fun reload() { order = read() }
    fun save(value: List<String>) {
        val next = normalizeMobileNavigation(value)
        check(preferences.edit().putString("order", JSONArray(next).toString()).commit()) { "导航设置未保存，请重试" }
        order = next
    }
}

@Composable
internal fun MobileNavigationEditor(settings: MobileNavigationSettings, close: () -> Unit) {
    var draft by remember { mutableStateOf(settings.order) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = close, title = { Text("自定义底栏") }, text = {
        Column {
            Text("前 5 项显示在底栏，第 6 个按钮固定为更多，其余入口收纳在更多中。", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                itemsIndexed(draft, key = { _, route -> route }) { index, route ->
                    val item = mobileDestinations.first { it.route == route }
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium)
                            Text(if (index < 5) "底栏 · 第 ${index + 1} 位" else "更多", style = MaterialTheme.typography.labelSmall)
                        }
                        if (index >= 5) TextButton(onClick = { draft = moveMobileDestination(draft, index, 4) }) { Text("加入底栏") }
                        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
                            IconButton(onClick = { draft = moveMobileDestination(draft, index, index - 1) }, enabled = index > 0, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ArrowUpward, "上移${item.label}") }
                            IconButton(onClick = { draft = moveMobileDestination(draft, index, index + 1) }, enabled = index < draft.lastIndex, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.ArrowDownward, "下移${item.label}") }
                        }
                    }
                }
            }
            TextButton(onClick = { draft = normalizeMobileNavigation(null) }) { Text("恢复默认") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = { runCatching { settings.save(draft) }.onSuccess { close() }.onFailure { error = it.message } }) { Text("保存") } },
        dismissButton = { TextButton(onClick = close) { Text("取消") } })
}

@Composable
internal fun MobileDestinationIcon(item: MobileDestination, modifier: Modifier = Modifier) {
    when (item.route) {
        "Codex" -> Icon(androidx.compose.ui.res.painterResource(com.chuckiehelper.mobile.R.drawable.ic_codex_official), null, modifier)
        "Claude Code" -> Icon(androidx.compose.ui.res.painterResource(com.chuckiehelper.mobile.R.drawable.ic_claude), null, modifier, tint = androidx.compose.ui.graphics.Color(0xFFD97757))
        "Hermes" -> HermesOfficialIcon(modifier)
        "Cloudflare" -> androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.chuckiehelper.mobile.R.drawable.ic_cloudflare), null, modifier)
        else -> Icon(item.icon, null, modifier)
    }
}

@Composable
internal fun FullMobileNavigation(order: List<String>, route: String, onRoute: (String) -> Unit) {
    val visible = normalizeMobileNavigation(order).take(5)
    NavigationBar {
        (visible + "更多").forEach { target ->
            val selected = if (target == "更多") route !in visible else target == route
            val item = mobileDestinations.firstOrNull { it.route == target }
            val colors = MaterialTheme.colorScheme
            val color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
            Column(Modifier.weight(1f).padding(horizontal = 2.dp, vertical = 6.dp).height(60.dp)
                .clip(MaterialTheme.shapes.medium).background(if (selected) colors.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                .selectable(selected, role = Role.Tab, onClick = { onRoute(target) }),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CompositionLocalProvider(LocalContentColor provides color) {
                    if (item == null) Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(24.dp)) else MobileDestinationIcon(item, Modifier.size(24.dp))
                    Text(item?.label ?: "更多", color = color, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
            }
        }
    }
}
