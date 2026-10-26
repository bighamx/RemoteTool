@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.chuckiehelper.mobile.phonecontrol.*
import kotlinx.coroutines.*
import org.json.JSONObject
import rikka.shizuku.Shizuku

private fun phoneObject(vararg fields: Pair<String, Any?>) = JSONObject().apply { fields.forEach { put(it.first, it.second) } }
private fun phoneQuery(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

@Composable
fun PhoneToolsScreen(api: PhoneWebApi?, close: () -> Unit) {
    var connectionsOpen by rememberSaveable { mutableStateOf(false) }
    if (connectionsOpen) { PhoneConnectionsScreen { connectionsOpen = false }; return }
    BackHandler(onBack = close)
    val context = LocalContext.current
    val engine = remember { PhoneEngine.get(context) }
    val settings = engine.settings
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    fun notice(text: String) { scope.launch { snackbar.showSnackbar(text.take(240)) } }
    var enabled by remember { mutableStateOf(PhoneControlService.running || PhoneControlService.starting) }
    var direct by remember { mutableStateOf(settings.direct) }
    var advanced by remember { mutableStateOf(settings.advanced) }
    var root by remember { mutableStateOf(settings.root) }
    var shizuku by remember { mutableStateOf(settings.shizuku) }
    var agentOverlay by remember { mutableStateOf(settings.agentOverlay) }
    var agentIsland by remember { mutableStateOf(settings.agentIsland) }
    var islandStatus by remember { mutableStateOf(HyperIslandStatus()) }
    var relay by remember(api?.base) { mutableStateOf(if (PhoneControlService.running) PhoneControlService.relayBase != null else api?.base?.let { settings.relayEnabled(it) } ?: false) }
    var relayStatus by remember { mutableStateOf(PhoneRelay.state) }
    var running by remember { mutableStateOf(PhoneControlService.running) }
    var serviceError by remember { mutableStateOf(PhoneControlService.error) }
    var autoRestore by remember { mutableStateOf(settings.autoRestore) }
    var autoStatus by remember { mutableStateOf(com.chuckiehelper.mobile.startup.PhoneAutoRestore.status) }
    var connectionCount by remember { mutableIntStateOf(engine.connectionStates().size) }
    var permissionEpoch by remember { mutableIntStateOf(0) }
    var permissionState by remember { mutableStateOf(PhonePermissionState()) }
    var accessibilityWanted by remember { mutableStateOf(settings.accessibilityWanted) }
    var accessibilityBusy by remember { mutableStateOf(false) }
    var accessibilityStatus by remember { mutableStateOf("") }
    var rootBusy by remember { mutableStateOf(false) }
    var webBusy by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(settings.address) }
    var connectionTab by remember(api?.base) { mutableStateOf(settings.connectionTab.takeIf { it == "direct" || it == "web" && api != null } ?: if (relay && !direct) "web" else "direct") }
    var permissionsOpen by rememberSaveable { mutableStateOf(false) }
    var specialOpen by rememberSaveable { mutableStateOf(false) }
    var extraOpen by rememberSaveable { mutableStateOf(false) }
    var webConnection by remember(api?.base) { mutableStateOf(api?.base?.let { settings.webConnection(it) }) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(permissionEpoch) { islandStatus = withContext(Dispatchers.IO) { HyperIslandSupport.query(context) }; engine.agentUi.refresh(true) }
    LaunchedEffect(permissionEpoch) {
        permissionState = withContext(Dispatchers.IO) { PhonePermissionStatus.read(context) }
        if (permissionState.notificationGranted == true && !permissionState.notificationRunning) PhoneNotifications.requestConnection(context)
        accessibilityWanted = settings.accessibilityWanted
        accessibilityBusy = PhoneAccessibilityControl.busy
        accessibilityStatus = PhoneAccessibilityControl.status
    }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) permissionEpoch++ }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionEpoch++
        notice("已授权 ${result.values.count { it }} / ${result.size} 项")
    }
    fun systemPage(action: String, packageSpecific: Boolean = false) {
        runCatching { context.startActivity(Intent(action).apply { if (packageSpecific) data = Uri.parse("package:${context.packageName}") }) }
            .onFailure { notice(it.message ?: "无法打开系统设置") }
    }
    fun copy(value: String, label: String) { clipboard.setText(AnnotatedString(value)); notice("已复制$label") }
    LaunchedEffect(Unit) {
        if (address.isBlank()) {
            val detected = withContext(Dispatchers.IO) { runCatching {
            java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress ?: "127.0.0.1"
            }.getOrDefault("127.0.0.1") }
            if (address.isBlank()) address = detected
        }
    }
    LaunchedEffect(Unit) {
        while (isActive) {
            running = PhoneControlService.running; enabled = running || PhoneControlService.starting
            serviceError = PhoneControlService.error; relayStatus = PhoneRelay.state
            connectionCount = engine.connectionStates().size
            autoStatus = com.chuckiehelper.mobile.startup.PhoneAutoRestore.status
            if (running) { direct = settings.direct; relay = PhoneControlService.relayBase != null }
            delay(1000)
        }
    }
    val backendStatus = remember(permissionEpoch, shizuku, enabled, relayStatus) { engine.privilege.status() }
    val runtimeGroups = buildList {
        add("相机与麦克风" to listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        if (Build.VERSION.SDK_INT >= 33) add("通知" to listOf(Manifest.permission.POST_NOTIFICATIONS))
        add("位置" to listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        add("联系人与日历" to listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        add("电话与短信" to listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_PHONE_NUMBERS, Manifest.permission.CALL_PHONE, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS))
        add("媒体文件" to if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO) else listOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        if (Build.VERSION.SDK_INT >= 31) add("蓝牙与附近设备" to (listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.NEARBY_WIFI_DEVICES) else emptyList()))
        add("传感器与活动识别" to (listOf(Manifest.permission.BODY_SENSORS) + if (Build.VERSION.SDK_INT >= 29) listOf(Manifest.permission.ACTIVITY_RECOGNITION) else emptyList()))
    }
    val grantedCount = remember(permissionEpoch, permissionState.granted) { runtimeGroups.sumOf { (_, group) -> group.count { it in permissionState.granted } } }
    Scaffold(
        topBar = { TopAppBar(title = { Text("手机操控") }, navigationIcon = { IconButton(onClick = close) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PhoneAndroid, null, Modifier.size(28.dp))
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(if (running) "服务运行中" else if (enabled) "正在启动" else "服务已停止", style = MaterialTheme.typography.titleMedium)
                            Text(if (running && PhoneControlService.relayBase != null) "转发通道 · $relayStatus" else buildList {
                                if (direct) add("直连手机 · HTTP / MCP")
                                if (relay) add("转发通道")
                            }.joinToString(" + ").ifBlank { "请选择接入方式" }, style = MaterialTheme.typography.bodySmall)
                        }
                        Button(onClick = {
                            if (enabled) { PhoneControlService.stop(context); enabled = false; running = false }
                            else try {
                                require(direct || relay && api != null) { "请先选择接入方式" }
                                PhoneControlService.start(context, api?.base?.takeIf { relay }, api?.cookie()?.takeIf { relay }); enabled = true
                            } catch (e: Exception) { enabled = false; serviceError = e.message; notice(e.message ?: "启动失败") }
                        }) { Text(if (enabled) "停止" else "启动") }
                    }
                }
                serviceError?.takeIf { !enabled }?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
            item { PhoneSettingsCard("接入方式") {
                PhoneSettingRow("直连手机", "本应用直接提供 HTTP/MCP 服务") { Switch(direct, { direct = it; settings.direct = it }, enabled = !enabled, colors = settingSwitchColors()) }
                HorizontalDivider()
                PhoneSettingRow("转发通道", "通过 RemoteTool 服务端转发请求") { Switch(relay, { relay = it; api?.base?.let { base -> settings.setRelayEnabled(base, it) }; if (it && !direct) { connectionTab = "web"; settings.connectionTab = "web" } }, enabled = !enabled && api != null, colors = settingSwitchColors()) }
            } }
            item { PhoneSettingsCard("连接管理") {
                PhoneActionRow("连接与日志", "$connectionCount 个当前连接") { connectionsOpen = true }
            } }
            item { PhoneSettingsCard("运行恢复") {
                PhoneSettingRow("自动恢复", "App 再次运行时，恢复此前开启的服务；手动停止后不恢复，与开机自启独立") {
                    Switch(autoRestore, { value ->
                        autoRestore = value; settings.autoRestore = value
                        if (value) {
                            if (enabled) { settings.desiredRunning = true; settings.restoreRelay = PhoneControlService.relayBase }
                            com.chuckiehelper.mobile.startup.PhoneAutoRestore.request(context)
                        } else com.chuckiehelper.mobile.startup.PhoneAutoRestore.cancel()
                    }, colors = settingSwitchColors())
                }
                if (autoRestore && autoStatus.isNotBlank()) Text(autoStatus, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            } }
            item { PhoneSettingsCard("连接配置") {
                Row(Modifier.padding(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(connectionTab == "direct", { connectionTab = "direct"; settings.connectionTab = "direct" }, label = { Text("直连手机") })
                    FilterChip(connectionTab == "web", { connectionTab = "web"; settings.connectionTab = "web" }, label = { Text("转发通道") }, enabled = api != null)
                }
                if (connectionTab == "direct") {
                    OutlinedTextField(address, { address = it; settings.address = it }, label = { Text("手机访问地址") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp))
                    Text("http://$address:${settings.port}/mcp", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                    FilledTonalButton(onClick = { copy(phoneObject("mcpServers" to phoneObject("remotetool-phone" to phoneObject("url" to "http://$address:${settings.port}/mcp", "headers" to phoneObject("Authorization" to "Bearer ${settings.token}")))).toString(2), "MCP 配置") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                        Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("复制 MCP 配置")
                    }
                    TextButton(onClick = { extraOpen = !extraOpen }, modifier = Modifier.padding(horizontal = 6.dp)) { Text(if (extraOpen) "收起其他操作" else "其他连接操作") }
                    if (extraOpen) {
                        PhoneActionRow("stdio 配置") { copy(phoneObject("mcpServers" to phoneObject("remotetool-phone" to phoneObject("command" to "python", "args" to org.json.JSONArray(listOf("/path/to/remotetool_phone.py", "mcp")), "env" to phoneObject("REMOTETOOL_PHONE_URL" to "http://$address:${settings.port}", "REMOTETOOL_PHONE_TOKEN" to settings.token)))).toString(2), "stdio 配置") }
                        PhoneActionRow("复制工具令牌") { copy(settings.token, "工具令牌") }
                        PhoneActionRow("重置直连令牌") { settings.rotateToken(); PhoneControlService.stop(context); enabled = false; notice("直连令牌已重置，旧直连配置已失效") }
                    }
                } else if (api != null) {
                    Text("通过 RemoteTool 服务端转发请求", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(14.dp))
                    FilledTonalButton(enabled = !webBusy, modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp), onClick = { scope.launch {
                        webBusy = true
                        try {
                            var connection = webConnection?.let { JSONObject(it) }
                            if (connection == null || connection.optBoolean("allow_advanced") != advanced) {
                                if (connection != null) api.json(api.request("/api/phone/v1/bindings/${phoneQuery(connection.getString("id"))}").newBuilder().delete().build())
                                connection = api.json("/api/phone/v1/connections", phoneObject("phone_id" to settings.id, "allow_advanced" to advanced)).put("allow_advanced", advanced)
                                webConnection = connection.toString(); settings.saveWebConnection(api.base, webConnection)
                            }
                            val mcpUrl = api.base.trimEnd('/') + "/api/phone/v1/bound/" + connection.getString("id") + "/mcp"
                            copy(phoneObject("mcpServers" to phoneObject("remotetool-phone" to phoneObject("url" to mcpUrl, "headers" to phoneObject("Authorization" to "Bearer ${connection.getString("token")}")))).toString(2), "MCP 配置")
                        } catch (e: Exception) { notice(e.message ?: "创建连接失败") }
                        finally { webBusy = false }
                    } }) { Text(if (webBusy) "正在处理…" else "复制 MCP 配置") }
                    if (webConnection != null) TextButton(enabled = !webBusy, modifier = Modifier.padding(horizontal = 6.dp), onClick = { scope.launch {
                        webBusy = true
                        try {
                            api.json(api.request("/api/phone/v1/bindings/${phoneQuery(JSONObject(webConnection!!).getString("id"))}").newBuilder().delete().build())
                            settings.saveWebConnection(api.base, null); webConnection = null; notice("MCP 连接已撤销")
                        } catch (e: Exception) { notice(e.message ?: "撤销连接失败") }
                        finally { webBusy = false }
                    } }) { Text("撤销 MCP 连接") }
                    Spacer(Modifier.height(10.dp))
                }
            } }
            item { PhoneSettingsCard("Agent 提示") {
                PhoneSettingRow("屏幕顶部提示", if (Settings.canDrawOverlays(context)) "连接、操作和协助请求" else "需要悬浮窗权限") {
                    if (!Settings.canDrawOverlays(context)) TextButton(onClick = { systemPage(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }) { Text("授权") }
                    Switch(agentOverlay, { agentOverlay = it; settings.agentOverlay = it; engine.agentUi.refresh(true) })
                }
                HorizontalDivider()
                PhoneSettingRow("HyperOS 超级岛", islandStatus.label) {
                    TextButton(onClick = { runCatching {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                    }.onFailure { notice("无法打开通知设置") } }) { Text("设置") }
                    Switch(agentIsland, { agentIsland = it; settings.agentIsland = it; engine.agentUi.refresh(true) },
                        enabled = islandStatus.protocol >= 2, colors = settingSwitchColors())
                }
            } }
            item { PhoneSettingsCard("操作权限") {
                PhoneSettingRow("无障碍", if (accessibilityBusy) "正在调整无障碍服务" else permissionState.accessibilityLabel) {
                    TextButton(onClick = { systemPage(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("设置") }
                    Switch(accessibilityWanted, { value ->
                        accessibilityWanted = value; accessibilityBusy = true
                        PhoneAccessibilityControl.setWanted(context, value) { permissionEpoch++ }
                    }, colors = settingSwitchColors())
                }
                if (accessibilityWanted && !permissionState.accessibilityRunning && accessibilityStatus.isNotBlank()) {
                    Text(accessibilityStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp))
                    if (!accessibilityBusy) TextButton(onClick = {
                        accessibilityBusy = true; PhoneAccessibilityControl.request(context, force = true) { permissionEpoch++ }
                    }, modifier = Modifier.padding(horizontal = 6.dp)) { Text("重试恢复") }
                }
                HorizontalDivider()
                PhoneSettingRow("Shizuku", if (backendStatus.optBoolean("shizuku")) "已授权 · UID ${backendStatus.optInt("shizuku_uid")}" else "未就绪") {
                    TextButton(onClick = { runCatching { check(Shizuku.pingBinder()) { "请先启动 Shizuku" }; Shizuku.requestPermission(731) }.onFailure { notice(it.message ?: "授权失败") } }) { Text("授权") }
                    Switch(shizuku, { shizuku = it; settings.shizuku = it })
                }
                HorizontalDivider()
                PhoneSettingRow("Root", if (root) "已启用" else "未启用") {
                    TextButton(enabled = !rootBusy, onClick = { scope.launch {
                        rootBusy = true
                        try {
                            val value = withContext(Dispatchers.IO) { PhoneProcesses.run(listOf("su", "-c", "id"), 30000) }
                            val granted = value.optInt("exit_code", -1) == 0 && value.optString("stdout").contains("uid=0")
                            root = granted; settings.root = granted; notice(if (granted) "Root 已授权 · UID 0" else "Root 未授权：${value.optString("stderr")}")
                        } catch (e: Exception) { notice("Root 不可用：${e.message}") }
                        finally { rootBusy = false }
                    } }) { Text(if (rootBusy) "请求中" else "授权") }
                    Switch(root, { root = it; settings.root = it }, enabled = !rootBusy, colors = settingSwitchColors())
                }
                HorizontalDivider()
                PhoneSettingRow("高级操作", "Shell、应用管理与系统修改") { Switch(advanced, { advanced = it; settings.advanced = it }) }
            } }
            item { PhoneSettingsCard("系统授权") {
                PhoneExpandRow("常用权限", "已授权 $grantedCount / ${runtimeGroups.sumOf { it.second.size }} 项", permissionsOpen) { permissionsOpen = !permissionsOpen }
                if (permissionsOpen) runtimeGroups.forEach { (title, permissions) ->
                    val count = permissions.count { it in permissionState.granted }
                    PhoneActionRow(title, "$count / ${permissions.size}") { permissionRequest.launch(permissions.toTypedArray()) }
                }
                HorizontalDivider()
                PhoneExpandRow("特殊权限", "通知读取、文件访问及系统设置", specialOpen) { specialOpen = !specialOpen }
                if (specialOpen) {
                    if (Build.VERSION.SDK_INT >= 29) PhoneActionRow("后台定位", PhonePermissionState.label(permissionState.backgroundLocation)) { if (Build.VERSION.SDK_INT == 29) permissionRequest.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) else systemPage(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
                    PhoneActionRow("通知读取", permissionState.notificationLabel) { systemPage(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
                    if (Build.VERSION.SDK_INT >= 30) PhoneActionRow("所有文件访问", PhonePermissionState.label(permissionState.files)) { systemPage(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, true) }
                    PhoneActionRow("悬浮窗", PhonePermissionState.label(permissionState.overlay)) { systemPage(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
                    PhoneActionRow("修改系统设置", PhonePermissionState.label(permissionState.writeSettings)) { systemPage(Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }
                    PhoneActionRow("勿扰控制", PhonePermissionState.label(permissionState.policy)) { systemPage(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
                    PhoneActionRow("使用情况访问", PhonePermissionState.label(permissionState.usage)) { systemPage(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
                    PhoneActionRow("安装应用", PhonePermissionState.label(permissionState.install)) { systemPage(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, true) }
                    PhoneActionRow("转到应用设置界面", icon = Icons.Outlined.SettingsApplications) { systemPage(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }
                }
            } }
        }
    }
}

@Composable
private fun PhoneSettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(vertical = 4.dp), content = content) }
    }
}

@Composable
private fun PhoneSettingRow(title: String, subtitle: String, actions: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
}

@Composable
private fun PhoneActionRow(title: String, status: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, click: () -> Unit) {
    Surface(onClick = click, color = androidx.compose.ui.graphics.Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { Icon(it, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)) }
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp)) }
            Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PhoneExpandRow(title: String, subtitle: String, expanded: Boolean, click: () -> Unit) {
    Surface(onClick = click, color = androidx.compose.ui.graphics.Color.Transparent) {
        PhoneSettingRow(title, subtitle) { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "收起" else "展开") }
    }
}
