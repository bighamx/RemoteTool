@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.isActive
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
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
    val list = rememberLazyListState()
    val currentOrder by rememberUpdatedState(draft)
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val edge = with(density) { 64.dp.toPx() }
    val scrollSpeed = with(density) { 600.dp.toPx() }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragMoved by remember { mutableStateOf(false) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var beforeDrag by remember { mutableStateOf<List<String>>(emptyList()) }
    fun reorder() {
        val key = dragging ?: return
        val center = dragTop + dragHeight / 2f
        val visible = list.layoutInfo.visibleItemsInfo.filter { it.key in currentOrder }
        // Wait for the preceding move to reach layout before interpreting another target.
        if (visible.any { row ->
            val index = currentOrder.indexOf(row.key)
            row.index != index + if (index >= 5) 1 else 0
        }) return
        val target = visible.minByOrNull { kotlin.math.abs(center - (it.offset + it.size / 2f)) } ?: return
        val from = currentOrder.indexOf(key)
        val to = currentOrder.indexOf(target.key)
        if (from != to && from >= 0 && to >= 0) {
            list.requestScrollToItem(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
            draft = moveMobileDestination(currentOrder, from, to)
        }
    }
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var previousFrame = 0L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val frame = withFrameNanos { it }
            val seconds = if (previousFrame == 0L) .016f else ((frame - previousFrame) / 1_000_000_000f).coerceAtMost(.032f)
            previousFrame = frame
            val layout = list.layoutInfo
            val step = when {
                !dragMoved -> 0f
                pointerY < layout.viewportStartOffset + edge -> -((layout.viewportStartOffset + edge - pointerY) / edge).coerceIn(0f, 1f) * scrollSpeed * seconds
                pointerY > layout.viewportEndOffset - edge -> ((pointerY - (layout.viewportEndOffset - edge)) / edge).coerceIn(0f, 1f) * scrollSpeed * seconds
                else -> 0f
            }
            if (step != 0f) { list.scrollBy(step); reorder() }
        }
    }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("你的底栏", style = MaterialTheme.typography.titleLarge)
                    Text("常用功能，放在顺手的位置", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                TextButton(enabled = dragging == null, onClick = { draft = normalizeMobileNavigation(null) }) { Text("恢复默认") }
            }
            Surface(shape = MaterialTheme.shapes.large, color = colors.secondaryContainer.copy(alpha = .55f)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("底栏预览 · 前 5 项", style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                        draft.take(5).forEach { route ->
                            val item = mobileDestinations.first { it.route == route }
                            Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                                MobileDestinationIcon(item, Modifier.size(22.dp))
                                Text(item.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(22.dp))
                            Text("更多", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text("长按条目拖动排序 · 前 5 项显示在底栏", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            LazyColumn(state = list, userScrollEnabled = dragging == null, modifier = Modifier.weight(1f).fillMaxWidth().testTag("navigation-order")
                .pointerInput(list) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { position ->
                            val row = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key in currentOrder && position.y >= it.offset && position.y < it.offset + it.size }
                            if (row != null) {
                                dragMoved = false; beforeDrag = currentOrder.toList(); dragging = row.key as String
                                dragTop = row.offset.toFloat(); dragHeight = row.size; pointerY = position.y
                                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, amount -> if (dragging != null) { dragMoved = true; change.consume(); dragTop += amount.y; pointerY += amount.y; reorder() } },
                        onDragEnd = { dragging = null },
                        onDragCancel = { if (dragging != null) draft = beforeDrag; dragging = null },
                    )
                }, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                draft.forEachIndexed { index, route ->
                    if (index == 5) item(key = "navigation-more-divider") {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            HorizontalDivider(Modifier.weight(1f))
                            Text("更多入口", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            HorizontalDivider(Modifier.weight(1f))
                        }
                    }
                    item(key = route) {
                        val item = mobileDestinations.first { it.route == route }
                        val pinned = index < 5
                        val active = dragging == route
                        val offset = if (active) dragTop - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == route }?.offset ?: dragTop.toInt()) else 0f
                        Column(Modifier.animateItem(placementSpec = if (active) null else androidx.compose.animation.core.spring())
                            .zIndex(if (active) 1f else 0f).graphicsLayer { translationY = offset; if (active) { scaleX = 1.025f; scaleY = 1.025f } }
                            .testTag("navigation-row-$route").semantics {
                                stateDescription = if (pinned) "底栏第 ${index + 1} 位" else "更多中的第 ${index - 4} 项"
                                customActions = buildList {
                                    if (index > 0) add(CustomAccessibilityAction("上移${item.label}") { draft = moveMobileDestination(draft, index, index - 1); true })
                                    if (index < draft.lastIndex) add(CustomAccessibilityAction("下移${item.label}") { draft = moveMobileDestination(draft, index, index + 1); true })
                                    if (!pinned) add(CustomAccessibilityAction("加入底栏") { draft = moveMobileDestination(draft, index, 4); true })
                                }
                            }) {
                            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = if (active) 6.dp else 0.dp, shadowElevation = if (active) 8.dp else 0.dp,
                                color = if (active) colors.primaryContainer else if (pinned) colors.secondaryContainer.copy(alpha = .55f) else colors.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (pinned) Surface(shape = androidx.compose.foundation.shape.CircleShape, color = colors.primary, modifier = Modifier.size(26.dp)) {
                                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Text("${index + 1}", color = colors.onPrimary, style = MaterialTheme.typography.labelMedium) }
                                    } else Icon(Icons.Outlined.MoreHoriz, null, Modifier.size(26.dp), tint = colors.onSurfaceVariant)
                                    MobileDestinationIcon(item, Modifier.size(24.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.label, style = MaterialTheme.typography.bodyMedium)
                                        Text(if (pinned) "显示在底栏" else "收纳在更多", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    }
                                    Icon(Icons.Outlined.DragHandle, "长按拖动${item.label}", Modifier.size(24.dp), tint = if (active) colors.primary else colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
            error?.let { Text(it, color = colors.error) }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = close, enabled = dragging == null, modifier = Modifier.weight(1f)) { Text("取消") }
                Button(enabled = dragging == null, onClick = { runCatching { settings.save(draft) }.onSuccess { close() }.onFailure { error = it.message } }, modifier = Modifier.weight(1f)) { Text("保存布局") }
            }
        }
    }
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
