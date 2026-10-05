@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chuckiehelper.mobile.RemoteActivity
import kotlinx.coroutines.*
import org.json.JSONObject

@Composable
fun NativeApp(model: NativeModel) {
    val savedScreens = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val dark = isSystemInDarkTheme()
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xff61dbc4),
                secondary = Color(0xff9bbbf4),
                background = Color(0xff10151d),
                surface = Color(0xff10151d),
                surfaceContainer = Color(0xff1b2431),
                surfaceContainerLow = Color(0xff17202a),
                surfaceContainerHigh = Color(0xff24313b),
                surfaceContainerHighest = Color(0xff293942),
                secondaryContainer = Color(0xff26463f),
                onSecondaryContainer = Color(0xffc6f4e8),
                surfaceTint = Color(0xff61dbc4),
            )
        else
            lightColorScheme(
                primary = Color(0xff006b59),
                secondary = Color(0xff395d92),
                background = Color(0xfff5f8fa),
                surface = Color(0xfff5f8fa),
                surfaceContainer = Color(0xffedf3f1),
                surfaceContainerLow = Color.White,
                surfaceContainerHigh = Color(0xffe5eeeb),
                surfaceContainerHighest = Color(0xffe0e9e5),
                secondaryContainer = Color(0xffdceee7),
                onSecondaryContainer = Color(0xff173d32),
                primaryContainer = Color(0xffc9f1e5),
                surfaceTint = Color(0xff006b59),
                outlineVariant = Color(0xffcad6d0),
            )
    MaterialTheme(colorScheme = colors) {
        val snackbar = remember { SnackbarHostState() }
        val backDispatcher =
            androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
                ?.onBackPressedDispatcher
        val session = model.session.takeUnless { model.browsingDevices }
        val pages = rememberSaveableStateHolder()
        var route by rememberSaveable { mutableStateOf("系统") }
        var detail by remember { mutableStateOf<String?>(null) }
        var composeTarget by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val keyboardVisible = WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
        LaunchedEffect(model.message) {
            model.message?.let {
                snackbar.showSnackbar(it)
                model.message = null
            }
        }
        LaunchedEffect(model.session) { detail = null }
        BackHandler(enabled = model.session != null) {
            if (model.browsingDevices) model.returnToDevice()
            else if (detail != null) detail = null else model.showDevices()
        }
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (session == null) "我的设备" else detail ?: route,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            if (session != null)
                                Text(
                                    session.device.name +
                                        " · " +
                                        session.channels
                                            .firstOrNull { it.url == session.api.base }
                                            ?.ms +
                                        " ms",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                        }
                    },
                    navigationIcon = {
                        if (detail != null || (model.browsingDevices && model.session != null))
                            IconButton(
                                onClick = {
                                    if (model.browsingDevices) model.returnToDevice()
                                    else if (detail in listOf("Hermes", "Codex")) backDispatcher?.onBackPressed()
                                    else detail = null
                                }
                            ) {
                                Icon(Icons.Outlined.ArrowBack, "返回")
                            }
                    },
                    actions = {
                        if (session != null)
                            IconButton(onClick = { model.openChannels(session.device) }) {
                                Icon(Icons.Outlined.Route, "连接通道")
                            }
                        if (session != null)
                            IconButton(onClick = { model.showDevices() }) {
                                Icon(Icons.Outlined.Devices, "切换设备")
                            }
                    },
                )
            },
            bottomBar = {
                if (session != null && !model.loginNeeded && detail == null && !keyboardVisible)
                    NavigationBar {
                        listOf(
                                "系统" to Icons.Outlined.Speed,
                                "远程" to Icons.Outlined.DesktopWindows,
                                "文件" to Icons.Outlined.Folder,
                                "Hermes" to Icons.Outlined.SmartToy,
                                "更多" to Icons.Outlined.MoreHoriz,
                            )
                            .forEach { (name, icon) ->
                                NavigationBarItem(
                                    selected = route == name,
                                    onClick = { route = name },
                                    icon = { Icon(icon, name) },
                                    label = { Text(name) },
                                )
                            }
                    }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when {
                    model.connecting && model.channelDevice == null ->
                        Column(
                            Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text("正在验证设备并检测通道…")
                        }
                    session == null -> DeviceScreen(model)
                    model.loginNeeded -> key(session.device.id) {
                        LoginScreen(session.api, model.savedUsername(session.device.id),
                            onLogin = { username, password -> model.login(session, username, password) },
                            onError = { model.message = it })
                    }
                    else ->
                        key(session.api.base, session.device.id) {
                            val api = session.api
                            val error: (String) -> Unit = {
                                if (it.contains("登录已过期")) model.loginNeeded = true
                                model.message = it
                            }
                            pages.SaveableStateProvider("${session.device.id}:${detail ?: route}") {
                                when (detail ?: route) {
                                    "系统" ->
                                        SystemScreen(
                                            api,
                                            onProcesses = { detail = "进程" },
                                            onTerminal = { detail = "终端" },
                                            onError = error,
                                        )
                                    "进程" -> ProcessScreen(api, error)
                                    "远程" -> RemoteScreen(api)
                                    "文件" -> savedScreens.SaveableStateProvider("files-${session.device.id}") {
                                        FilesScreen(api, error) { path ->
                                            composeTarget = path
                                            detail = "Compose 管理"
                                        }
                                    }
                                    "Docker" -> DockerScreen(api, error)
                                    "Compose 管理" -> ComposeScreen(api, error, composeTarget)
                                    "终端" -> TerminalScreen(api, error)
                                    "Hangfire" -> JobsScreen(api, error)
                                    "Hermes" -> savedScreens.SaveableStateProvider("hermes-${session.device.id}") { HermesScreen(api, session.device.id) }
                                    "Codex" -> savedScreens.SaveableStateProvider("codex-${session.device.id}") { HermesScreen(api, session.device.id, "codex") }
                                    else ->
                                        Column(
                                            Modifier.padding(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(12.dp),
                                        ) {
                                            ToolRow("Codex", "会话 · 模型 · 账户与工作空间", Icons.Outlined.Code) { detail = "Codex" }
                                            ToolRow(
                                                "Docker",
                                                "容器 · Compose · 镜像管理",
                                                Icons.Outlined.Layers,
                                            ) {
                                                detail = "Docker"
                                            }
                                            ToolRow(
                                                "命令终端",
                                                "Shell · PowerShell · CMD",
                                                Icons.Outlined.Terminal,
                                            ) {
                                                detail = "终端"
                                            }
                                            ToolRow(
                                                "Hangfire",
                                                "任务、队列与执行历史",
                                                Icons.Outlined.Schedule,
                                            ) {
                                                detail = "Hangfire"
                                            }
                                            ToolRow("进程管理", "查看资源占用与结束进程", Icons.Outlined.Memory) {
                                                detail = "进程"
                                            }
                                            ToolRow(
                                                "连接通道",
                                                "当前：${session.api.base}\n检测所有地址并手动选择",
                                                Icons.Outlined.Route,
                                            ) {
                                                model.openChannels(session.device)
                                            }
                                        }
                                }
                            }
                        }
                }
            }
        }
        model.channelDevice?.let { ChannelDialog(model, it) }
    }
}

@Composable
fun ToolRow(title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Outlined.ChevronRight, null)
        }
    }
}

@Composable
fun ErrorPane(error: String?, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (error == null) CircularProgressIndicator()
        else {
            Icon(Icons.Outlined.CloudOff, null)
            Text(error, modifier = Modifier.padding(vertical = 12.dp))
            OutlinedButton(onClick = retry) { Text("重试") }
        }
    }
}

@Composable
fun InfoRows(value: JSONObject) {
    value.keys().asSequence().toList().forEach { key ->
        val v = value.opt(key)
        if (v != null && v.toString() != "null")
            Column(Modifier.padding(vertical = 5.dp)) {
                Text(
                    key,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SelectionContainerCompat(v.toString())
            }
    }
}

@Composable
fun SelectionContainerCompat(value: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeviceScreen(model: NativeModel) {
    var addTo by remember { mutableStateOf<Device?>(null) }
    var adding by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf<Device?>(null) }
    var delete by remember { mutableStateOf<Device?>(null) }
    var rename by remember { mutableStateOf<Device?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Text("连接时使用上次的通道，可在设备内手动切换。", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(model.devices, key = { it.id }) { device ->
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Computer,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            device.name,
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        IconButton(onClick = { manage = device }) {
                            Icon(Icons.Outlined.Settings, "管理设备")
                        }
                    }
                    Text(
                        "${device.endpoints.size} 个地址 · ${device.id.take(8)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        onClick = { model.connect(device) },
                        enabled = device.endpoints.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("连接设备")
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    addTo = null
                    adding = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Add, null)
                Text("添加设备", Modifier.padding(start = 8.dp))
            }
        }
    }
    if (adding) AddDeviceDialog(model, addTo) { adding = false }
    manage?.let { device ->
        ModalBottomSheet(onDismissRequest = { manage = null }) {
            Column(Modifier.padding(20.dp)) {
                Text(device.name, style = MaterialTheme.typography.headlineSmall)
                device.endpoints.forEach { url ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(url, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        IconButton(
                            onClick = {
                                model.removeEndpoint(device, url)
                                manage = model.devices.find { it.id == device.id }
                            }
                        ) {
                            Icon(Icons.Outlined.RemoveCircleOutline, "移除地址")
                        }
                    }
                }
                FilledTonalButton(
                    onClick = {
                        addTo = device
                        manage = null
                        adding = true
                    }
                ) {
                    Text("添加地址")
                }
                TextButton(
                    onClick = {
                        manage = null
                        rename = device
                    }
                ) {
                    Text("修改名称")
                }
                TextButton(
                    onClick = {
                        manage = null
                        delete = device
                    }
                ) {
                    Text("删除设备", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    delete?.let { d ->
        ConfirmDialog("删除设备", "仅移除 App 中的 ${d.name} 配置。", { delete = null }) {
            model.forget(d)
            delete = null
        }
    }
    rename?.let { d ->
        InputDialog("修改名称", "设备名称", d.name, { rename = null }) { name ->
            model.rename(d, name)
            rename = null
        }
    }
}

@Composable
private fun AddDeviceDialog(model: NativeModel, device: Device?, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!busy) close() },
        title = { Text(if (device == null) "添加设备" else "添加地址") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (device == null)
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("设备名称") },
                        singleLine = true,
                    )
                OutlinedTextField(
                    url,
                    { url = it },
                    label = { Text("http:// 或 https:// 地址") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            model.add(name, url, device?.id)
                            close()
                        } catch (e: Exception) {
                            error = e.message
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text("验证并保存")
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("取消") } },
    )
}

@Composable
private fun LoginScreen(api: NativeApi, savedUsername: String, onLogin: suspend (String, String) -> Unit, onError: (String) -> Unit) {
    var username by rememberSaveable { mutableStateOf(savedUsername) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            Icons.Outlined.Lock,
            null,
            Modifier.size(40.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Text("登录电脑", style = MaterialTheme.typography.headlineMedium)
        Text(api.base, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            username,
            { username = it },
            label = { Text("用户名") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Button(
            enabled = !busy && username.isNotBlank() && password.isNotEmpty(),
            onClick = {
                busy = true
                scope.launch {
                    try {
                        onLogin(username, password)
                        password = ""
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        onError(e.message ?: "登录失败")
                    } finally {
                        busy = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "正在登录…" else "登录")
        }
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = confirm) { Text("确认") } },
        dismissButton = { TextButton(onClick = close) { Text("取消") } },
    )
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
