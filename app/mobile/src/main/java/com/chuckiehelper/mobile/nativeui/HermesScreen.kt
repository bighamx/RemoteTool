@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import org.json.JSONObject

@Composable
fun HermesScreen(api: NativeApi, deviceId: String) {
    val app = LocalContext.current.applicationContext as Application
    val model: HermesModel =
        viewModel(
            key = "hermes-$deviceId",
            factory =
                remember(deviceId) {
                    object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return HermesModel(app, deviceId) as T
                        }
                    }
                },
        )
    LaunchedEffect(api.base) { model.bind(api) }
    var list by rememberSaveable { mutableStateOf(true) }
    var renameChat by remember { mutableStateOf<JSONObject?>(null) }
    var deleteChat by remember { mutableStateOf<JSONObject?>(null) }
    var models by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    androidx.activity.compose.BackHandler(enabled = !list) { list = true }
    var filesDialog by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf(false) }
    var composerCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var commandBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var attachmentBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var commandPanelBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var attachmentPanelBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    val imagePicker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            uri?.let { model.upload(it, true) }
        }
    val filePicker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { model.upload(it, false) }
        }
    val context = LocalContext.current
    val scroll = rememberLazyListState()
    LaunchedEffect(model.selectedId, model.messages.size, list) {
        if (!list && model.messages.isNotEmpty()) {
            withFrameNanos {}
            val last = scroll.layoutInfo.totalItemsCount - 1
            if (last >= 0) scroll.scrollToItem(last)
        }
    }
    LaunchedEffect(model.pendingText.length) {
        val atBottom =
            scroll.layoutInfo.visibleItemsInfo.lastOrNull()?.index ==
                scroll.layoutInfo.totalItemsCount - 1
        if (!list && !scroll.isScrollInProgress && atBottom) {
            withFrameNanos {}
            val last = scroll.layoutInfo.totalItemsCount - 1
            if (last >= 0) scroll.scrollToItem(last)
        }
    }
    fun createChat() {
        model.newSession(newHermesChatName()) { list = false }
    }
    fun command(value: String) {
        when (value.trim().substringBefore(' ')) {
            "/new" -> createChat()
            "/model" -> {
                models = true
                model.fetchModels()
            }
            "/sessions" -> list = true
            "/stop" -> stop = true
            "/status" -> model.reconnect()
            "/providers" -> {
                providers = true
                model.fetchProviders()
            }
            else -> model.error = "支持 /new /model /sessions /stop /status /providers"
        }
    }
    Column(Modifier.fillMaxSize().imePadding()
        .onGloballyPositioned { composerCoordinates = it }
        .pointerInput(commands, attachMenu) {
            if (commands || attachMenu) awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val down = event.changes.firstOrNull { it.pressed && !it.previousPressed }
                    val coordinates = composerCoordinates
                    if (down != null && coordinates?.isAttached == true) {
                        val point = coordinates.localToWindow(down.position)
                        if (!commandBounds.contains(point) && !attachmentBounds.contains(point) &&
                            !(commands && commandPanelBounds.contains(point)) &&
                            !(attachMenu && attachmentPanelBounds.contains(point))) {
                            commands = false
                            attachMenu = false
                        }
                    }
                }
            }
        }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).clickable(enabled = !list && model.selectedId != null) {
                renameChat = obj("id" to model.selectedId, "title" to model.title)
            }) {
                Text(
                    if (list) "Hermes 会话" else model.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    model.runtime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = {
                    list = !list
                    model.refresh()
                }
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, "会话列表")
            }
            IconButton(onClick = { createChat() }, enabled = !model.submitting) { Icon(Icons.Outlined.Add, "新建会话") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Hermes 菜单") }
                DropdownMenu(menu, { menu = false }) {
                    listOf("模型选择" to "/model", "Provider 管理" to "/providers", "刷新与重连" to "/status")
                        .forEach { (name, value) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    menu = false
                                    command(value)
                                },
                            )
                        }
                }
            }
        }
        model.error?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { model.reconnect() }) { Text("重新连接") }
                        if (model.hasPendingSubmission)
                            TextButton(onClick = { model.retryPending() }) { Text("核对并重试原任务") }
                        TextButton(onClick = { model.error = null }) { Text("收起") }
                    }
                }
            }
        }
        if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (list) {
            OutlinedTextField(
                filter,
                { filter = it },
                label = { Text("搜索会话") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (model.sessions.isEmpty() && !model.loading)
                    item { Text("暂无会话。点击 + 开始与 Hermes 对话。") }
                items(
                    model.sessions.filter {
                        it.optString("title").contains(filter, true) ||
                            it.optString("preview").contains(filter, true)
                    },
                    key = { it.getString("id") },
                ) { session ->
                    Card(
                        onClick = {
                            model.select(session)
                            list = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                session.optString("title").ifBlank { "未命名会话" },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            var actions by remember { mutableStateOf(false) }
                            Box {
                                IconButton(onClick = { actions = true }) { Icon(Icons.Outlined.MoreVert, "会话操作") }
                                DropdownMenu(actions, { actions = false }) {
                                    DropdownMenuItem(text = { Text("修改名称") }, onClick = { actions = false; renameChat = session })
                                    DropdownMenuItem(text = { Text("删除会话", color = MaterialTheme.colorScheme.error) }, onClick = { actions = false; deleteChat = session })
                                }
                            }
                            }
                            Text(
                                session.optString("preview"),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                "${session.optString("source")} · ${session.optInt("message_count")} 条消息",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (model.hasMore)
                    item { TextButton(onClick = { model.moreSessions() }) { Text("加载更多会话") } }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                state = scroll,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(model.messages) { message ->
                    MessageBubble(message.role, message.text, message.attachments, api, model.files)
                }
                if (model.runId != null && model.runSession == model.selectedId) {
                    item {
                        if (model.pendingText.isNotBlank())
                            MessageBubble("assistant", model.pendingText, api = api, availableFiles = model.files)
                    }
                    item {
                        var showTools by remember { mutableStateOf(false) }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        model.state,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    TextButton(onClick = { stop = true }) { Text("停止") }
                                }
                                if (model.events.isNotEmpty())
                                    TextButton(onClick = { showTools = !showTools }) {
                                        Text("工具与进度 · ${model.events.size}")
                                    }
                                if (showTools)
                                    model.events.forEach {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(
                                                    it.text,
                                                    maxLines = 1,
                                                    overflow =
                                                        androidx.compose.ui.text.style.TextOverflow
                                                            .Ellipsis,
                                                    style = MaterialTheme.typography.labelLarge,
                                                )
                                                if (it.detail.isNotBlank())
                                                    Text(
                                                        it.detail,
                                                        maxLines = 1,
                                                        overflow =
                                                            androidx.compose.ui.text.style
                                                                .TextOverflow
                                                                .Ellipsis,
                                                        style = MaterialTheme.typography.bodySmall,
                                                    )
                                            }
                                            Text(
                                                it.type,
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        }
                                    }
                            }
                        }
                    }
                }
                model.approval?.let { approval ->
                    item {
                        ElevatedCard {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text("Hermes 等待你的决定", style = MaterialTheme.typography.titleSmall)
                                SelectionContainer {
                                    Text(
                                        approval.optString("description").ifBlank {
                                            approval.optString("command")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                if (approval.optString("command").isNotBlank())
                                    SelectionContainer {
                                        Text(
                                            approval.optString("command"),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                Row {
                                    FilledTonalButton(onClick = { model.approve("once") }) {
                                        Text("仅允许本次")
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedButton(onClick = { model.approve("deny") }) {
                                        Text("拒绝")
                                    }
                                }
                            }
                        }
                    }
                }
                if (model.selectedId == null) item { Text("请先选择会话或新建会话") }
                item("conversation-bottom") { Spacer(Modifier.height(1.dp)) }
            }
            if (model.uploading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("上传附件中…", style = MaterialTheme.typography.labelSmall)
            }
            if (model.pendingFiles.isNotEmpty())
                androidx.compose.foundation.lazy.LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(model.pendingFiles, key = { it.getString("id") }) { file ->
                        InputChip(
                            selected = false,
                            onClick = {
                                if (file.optString("mime").startsWith("image/")) preview = file
                            },
                            label = { Text(file.getString("name"), maxLines = 1) },
                            trailingIcon = {
                                IconButton(
                                    onClick = { model.removePendingFile(file.getString("id")) },
                                    modifier = Modifier.size(24.dp),
                                ) {
                                    Icon(Icons.Outlined.Close, "移除附件")
                                }
                            },
                        )
                    }
                }
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 2.dp,
            ) {
                Row(
                    Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.activity.compose.BackHandler(enabled = commands || attachMenu) {
                        commands = false
                        attachMenu = false
                    }
                    Box(Modifier.onGloballyPositioned { commandBounds = it.boundsInWindow() }) {
                        IconButton(
                            onClick = {
                                commands = !commands
                                attachMenu = false
                            },
                            modifier = Modifier.size(40.dp).focusProperties { canFocus = false },
                        ) {
                            Icon(
                                if (commands) Icons.Outlined.Close else Icons.Outlined.Menu,
                                "快捷命令",
                                modifier = Modifier.size(21.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        ComposerMenu(commands, { commands = false }, onBounds = { commandPanelBounds = it }) {
                            listOf(
                                    "新建会话" to "/new",
                                    "切换模型" to "/model",
                                    "会话历史" to "/sessions",
                                    "Provider 管理" to "/providers",
                                    "状态与重连" to "/status",
                                    "停止任务" to "/stop",
                                )
                                .forEach { (label, value) ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                Modifier.widthIn(min = 210.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                            ) {
                                                Text(label)
                                                Text(
                                                    value,
                                                    color =
                                                        MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        onClick = {
                                            commands = false
                                            command(value)
                                        },
                                    )
                                }
                        }
                    }
                    androidx.compose.foundation.text.BasicTextField(
                        value = model.draft,
                        onValueChange = { model.draft = it },
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp, vertical = 9.dp),
                        textStyle =
                            MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                        cursorBrush =
                            androidx.compose.ui.graphics.SolidColor(
                                MaterialTheme.colorScheme.primary
                            ),
                        maxLines = 5,
                        decorationBox = { inner ->
                            if (model.draft.isEmpty())
                                Text(
                                    "消息",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            inner()
                        },
                    )
                    Box(Modifier.onGloballyPositioned { attachmentBounds = it.boundsInWindow() }) {
                        IconButton(
                            onClick = {
                                attachMenu = !attachMenu
                                commands = false
                            },
                            modifier = Modifier.size(40.dp).focusProperties { canFocus = false },
                            enabled = model.selectedId != null && !model.uploading,
                        ) {
                            Icon(Icons.Outlined.AttachFile, "发送附件", modifier = Modifier.size(20.dp))
                        }
                        ComposerMenu(attachMenu, { attachMenu = false }, alignRight = true, onBounds = { attachmentPanelBounds = it }) {
                            DropdownMenuItem(
                                text = { Text("查看会话附件") },
                                onClick = {
                                    attachMenu = false
                                    filesDialog = true
                                    model.refreshFiles()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("选择图片") },
                                onClick = {
                                    attachMenu = false
                                    imagePicker.launch(
                                        androidx.activity.result.PickVisualMediaRequest(
                                            androidx.activity.result.contract
                                                .ActivityResultContracts
                                                .PickVisualMedia
                                                .ImageOnly
                                        )
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("选择文件（500 MB 以内）") },
                                onClick = {
                                    attachMenu = false
                                    filePicker.launch(arrayOf("*/*"))
                                },
                            )
                        }
                    }
                    val canSend =
                        (model.selectedId != null || model.draft.trim().startsWith("/")) &&
                            !model.submitting &&
                            !model.uploading &&
                            (model.draft.isNotBlank() || model.pendingFiles.isNotEmpty())
                    Surface(
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color =
                            if (canSend || model.runId != null) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        modifier = Modifier.size(40.dp),
                    ) {
                        IconButton(
                            onClick = {
                                if (model.runId != null) stop = true
                                else if (model.draft.trim().startsWith("/")) {
                                    command(model.draft)
                                    model.draft = ""
                                } else model.send()
                            },
                            enabled = canSend || model.runId != null,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                if (model.runId != null) Icons.Outlined.Stop
                                else Icons.Outlined.Send,
                                if (model.runId != null) "停止任务" else "发送消息",
                                modifier = Modifier.size(21.dp),
                                tint =
                                    if (canSend || model.runId != null)
                                        MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    renameChat?.let { chat ->
        InputDialog(
            "修改会话名称",
            "会话名称",
            chat.optString("title"),
            { renameChat = null },
        ) { name ->
            model.renameSession(chat.getString("id"), name) { renameChat = null }
        }
    }
    deleteChat?.let { chat ->
        ConfirmDialog("删除会话", "删除“${chat.optString("title") }”及其消息历史，无法撤销。附件按服务端清理规则保留。", { deleteChat = null }) {
            val selected = model.selectedId == chat.getString("id")
            model.deleteSession(chat.getString("id")) { if (selected) list = true }
            deleteChat = null
        }
    }
    if (stop)
        ConfirmDialog("停止 Hermes 任务", "请求在安全中断点停止，不会撤销已经执行的操作。", { stop = false }) {
            model.stop()
            stop = false
        }
    if (models) HermesModelPicker(model) { models = false }
    if (providers) HermesProviderDialog(model) { providers = false }
    if (filesDialog) HermesFilesDialog(api, model.files) { filesDialog = false }
    preview?.let { file ->
        MediaViewer(api, file, { preview = null }, { downloadHermesFile(context, api, file) })
    }
}

@Composable
private fun ComposerMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    alignRight: Boolean = false,
    onBounds: (androidx.compose.ui.geometry.Rect) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gap = with(density) { 4.dp.roundToPx() }
    val edge = with(density) { 8.dp.roundToPx() }
    val boundsCallback by rememberUpdatedState(onBounds)
    val position = remember(gap, edge, alignRight) {
        object : androidx.compose.ui.window.PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: androidx.compose.ui.unit.IntRect,
                windowSize: androidx.compose.ui.unit.IntSize,
                layoutDirection: androidx.compose.ui.unit.LayoutDirection,
                popupContentSize: androidx.compose.ui.unit.IntSize,
            ): androidx.compose.ui.unit.IntOffset {
                val x = if (alignRight) anchorBounds.right - popupContentSize.width else anchorBounds.left
                val offset = androidx.compose.ui.unit.IntOffset(
                    x.coerceIn(edge, (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge)),
                    (anchorBounds.top - popupContentSize.height - gap).coerceAtLeast(edge),
                )
                boundsCallback(androidx.compose.ui.geometry.Rect(
                    offset.x.toFloat(), offset.y.toFloat(),
                    (offset.x + popupContentSize.width).toFloat(), (offset.y + popupContentSize.height).toFloat()))
                return offset
            }
        }
    }
    androidx.compose.ui.window.Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shadowElevation = 6.dp,
        ) {
            Column(
                Modifier.width(IntrinsicSize.Max).heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    role: String,
    text: String,
    attachments: List<JSONObject> = emptyList(),
    api: NativeApi? = null,
    availableFiles: List<JSONObject> = emptyList(),
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    val presentation = presentHermesMessage(text, attachments, availableFiles)
    val hasImages = presentation.files.any { hermesFileKind(it) == "图片" }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (role == "user" && !hasImages)
                Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color =
                if (role == "user") MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = if (hasImages) 268.dp else 600.dp),
        ) {
            SelectionContainer {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        if (role == "user") "你" else "Hermes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(5.dp))
                    if (presentation.text.isNotBlank()) HermesMarkdown(presentation.text)
                    presentation.unavailable.forEach { name ->
                        Text("附件暂不可用：$name", style = MaterialTheme.typography.bodySmall)
                    }
                    if (api != null)
                        presentation.files.forEach { file ->
                            if (hermesFileKind(file) == "图片") {
                                Spacer(Modifier.height(8.dp))
                                coil.compose.AsyncImage(
                                    model =
                                        coil.request.ImageRequest.Builder(context)
                                            .data(api.base + file.getString("url"))
                                            .setHeader("Cookie", api.cookie())
                                            .build(),
                                    contentDescription = file.getString("name"),
                                    modifier =
                                        Modifier.widthIn(max = 240.dp).fillMaxWidth()
                                            .heightIn(min = 80.dp, max = 220.dp)
                                            .clickable { preview = file },
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    alignment = Alignment.CenterStart,
                                )
                            } else if (hermesFileKind(file) == "视频") HermesVideoThumbnail(api, file, { preview = file })
                            else HermesAttachmentCard(file, { preview = file })
                        }
                }
            }
        }
    }
    preview?.let { file ->
        if (api != null)
            HermesAttachmentViewer(api, file) { preview = null }
    }
}

@Composable
private fun HermesModelPicker(model: HermesModel, onClose: () -> Unit) {
    var global by remember { mutableStateOf(model.selectedId == null) }
    var provider by remember { mutableStateOf<JSONObject?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    fun previous() {
        provider = null
        selected = null
        filter = ""
        model.modelWarning = null
    }
    AlertDialog(
        onDismissRequest = { if (provider != null) previous() else onClose() },
        title = { Text(if (provider == null) "1 · 选择 Provider" else "2 · 选择模型") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    FilterChip(
                        !global,
                        {
                            global = false
                            model.modelWarning = null
                        },
                        label = { Text("当前会话") },
                        enabled = model.selectedId != null,
                    )
                    Spacer(Modifier.width(8.dp))
                    FilterChip(
                        global,
                        {
                            global = true
                            model.modelWarning = null
                        },
                        label = { Text("全局默认") },
                    )
                }
                OutlinedTextField(
                    filter,
                    { filter = it },
                    label = { Text(if (provider == null) "搜索 Provider" else "搜索模型") },
                    singleLine = true,
                )
                Text(
                    "默认：${model.modelOptions.optString("provider")} · ${model.modelOptions.optString("model")}",
                    style = MaterialTheme.typography.labelSmall,
                )
                LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    if (provider == null) {
                        items(
                            model.modelOptions.array("providers").objects().filter {
                                (it.optBoolean("authenticated") ||
                                    it.optBoolean("is_user_defined")) &&
                                    it.optString("name").contains(filter, true)
                            }
                        ) { p ->
                            Card(
                                onClick = {
                                    provider = p
                                    filter = ""
                                },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        p.optString("name", p.optString("slug")),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "${p.array("models").length()} 个模型" +
                                            (if (p.optBoolean("is_current")) " · 默认 Provider"
                                            else ""),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                    } else {
                        val choices = provider!!.array("models")
                        val rows =
                            (0 until choices.length())
                                .map {
                                    val item = choices.opt(it)
                                    if (item is JSONObject)
                                        item.optString(
                                            "id",
                                            item.optString("model", item.optString("name")),
                                        )
                                    else item.toString()
                                }
                                .filter { it.contains(filter, true) }
                        item {
                            Text(
                                provider!!.optString("name"),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            TextButton(onClick = { previous() }) { Text("上一步") }
                        }
                        items(rows) { name ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    selected = name
                                    model.modelWarning = null
                                },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected == name,
                                    {
                                        selected = name
                                        model.modelWarning = null
                                    },
                                )
                                Text(
                                    name,
                                    Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
                model.modelWarning?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                model.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            if (provider != null)
                TextButton(
                    onClick = {
                        selected?.let {
                            model.setModel(
                                provider!!.getString("slug"),
                                it,
                                global,
                                onClose,
                                confirm = model.modelWarning != null,
                            )
                        }
                    },
                    enabled = selected != null,
                ) {
                    Text(
                        if (model.modelWarning != null) "确认并继续"
                        else if (global) "设为全局默认" else "切换会话模型"
                    )
                }
        },
        dismissButton = {
            TextButton(onClick = { if (provider != null) previous() else onClose() }) {
                Text(if (provider != null) "上一步" else "取消")
            }
        },
    )
}

@Composable
private fun HermesProviderDialog(model: HermesModel, onClose: () -> Unit) {
    var editing by remember { mutableStateOf<JSONObject?>(null) }
    var adding by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Provider 管理") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                item {
                    Text(
                        "新增或编辑 OpenAI / Responses / Anthropic 兼容端点。已有密钥不会返回手机。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(model.providers.array("endpoints").objects()) { provider ->
                    Card(
                        onClick = {
                            editing = provider
                            adding = true
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                provider.optString("name", provider.optString("id")),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                provider.optString("base_url"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                provider.optString("model"),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    editing = null
                    adding = true
                }
            ) {
                Text("新增 Provider")
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("关闭") } },
    )
    if (adding) {
        val original = editing
        var name by remember(original) { mutableStateOf(original?.optString("name").orEmpty()) }
        var url by remember(original) { mutableStateOf(original?.optString("base_url").orEmpty()) }
        var selectedModel by
            remember(original) { mutableStateOf(original?.optString("model").orEmpty()) }
        var key by remember(original) { mutableStateOf("") }
        var transport by
            remember(original) { mutableStateOf(original?.optString("api_mode").orEmpty()) }
        AlertDialog(
            onDismissRequest = {
                adding = false
                key = ""
            },
            title = { Text(if (original == null) "新增 Provider" else "编辑 Provider") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        name,
                        { name = it },
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        url,
                        { url = it },
                        label = { Text("API Base URL") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        selectedModel,
                        { selectedModel = it },
                        label = { Text("默认模型 ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        key,
                        { key = it },
                        label = {
                            Text(if (original == null) "API Key（本地免鉴权可空）" else "新 API Key（留空保留已有）")
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    listOf(
                            "" to "自动识别",
                            "chat_completions" to "Chat Completions",
                            "codex_responses" to "Responses API",
                            "anthropic_messages" to "Anthropic Messages",
                        )
                        .forEach { (mode, label) ->
                            FilterChip(
                                transport == mode,
                                { transport = mode },
                                label = { Text(label) },
                            )
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val body =
                            obj(
                                "name" to name,
                                "base_url" to url,
                                "model" to selectedModel,
                                "api_mode" to transport,
                                "make_default" to false,
                            )
                        original?.optString("id")?.let { body.put("id", it) }
                        if (key.isNotBlank()) body.put("api_key", key)
                        model.saveProvider(body) {
                            adding = false
                            key = ""
                        }
                    },
                    enabled = name.isNotBlank() && url.isNotBlank() && selectedModel.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        adding = false
                        key = ""
                    }
                ) {
                    Text("取消")
                }
            },
        )
    }
}
