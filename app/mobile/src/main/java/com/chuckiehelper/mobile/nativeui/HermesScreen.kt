@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.DriveFileMove
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.json.JSONObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

@Composable
fun HermesScreen(api: NativeApi, deviceId: String, agent: String = "hermes") {
    val app = LocalContext.current.applicationContext as Application
    val model: HermesModel =
        viewModel(
            key = "$agent-$deviceId",
            factory =
                remember(deviceId, agent) {
                    object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return HermesModel(app, deviceId, agent) as T
                        }
                    }
                },
        )
    val agentName = model.agentName
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(api.base) { model.bind(api) }
    LaunchedEffect(api.base, agent, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (agent == "codex") while (true) { awaitUiRead(model.fetchUsage()); kotlinx.coroutines.delay(agentUsagePollDelay(model.hasKnownActivity)) }
        }
    }
    LaunchedEffect(model, lifecycle, model.hasKnownActivity) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (model.hasKnownActivity) while (true) { model.tickActivity(); kotlinx.coroutines.delay(5000) }
        }
    }
    var list by rememberSaveable { mutableStateOf(true) }
    DisposableEffect(model, list, lifecycle) {
        fun update() { model.setObserving(!list && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer)
        update()
        onDispose { lifecycle.removeObserver(observer); model.setObserving(false) }
    }
    var renameChat by remember { mutableStateOf<JSONObject?>(null) }
    var moveChat by remember { mutableStateOf<JSONObject?>(null) }
    var detailsChat by remember { mutableStateOf<JSONObject?>(null) }
    var deleteChat by remember { mutableStateOf<JSONObject?>(null) }
    var handoverSession by remember { mutableStateOf<JSONObject?>(null) }
    var models by remember { mutableStateOf(false) }
    var providers by remember { mutableStateOf(false) }
    var titleModelDialog by remember { mutableStateOf(false) }
    var accountsPanel by remember { mutableStateOf(false) }
    var createCodex by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf(false) }
    var takeover by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("") }
    var pendingInfo by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<Pair<String, HermesMessage>?>(null) }
    var editText by remember { mutableStateOf("") }
    var editAttachments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var editBoundary by remember { mutableStateOf<MessageEditBoundary?>(null) }
    LaunchedEffect(model.selectedId, list) {
        if (list || editTarget?.first != model.selectedId) editTarget = null
    }
    var questionPanel by remember { mutableStateOf(false) }
    LaunchedEffect(pendingInfo, model.selectedId, model.hasPendingSubmission) {
        if (pendingInfo && !model.hasPendingSubmission) pendingInfo = false
    }
    LaunchedEffect(model.selectedId, model.controlMessage) {
        val message = model.controlMessage ?: return@LaunchedEffect
        kotlinx.coroutines.delay(5000)
        model.dismissControlMessage(message)
    }
    LaunchedEffect(model.selectedId, list, api.base, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!list && model.selectedId != null) while (true) { awaitUiRead(model.pollContext()); kotlinx.coroutines.delay(agentContextPollDelay(model.hasExecution)) }
        }
    }
    LaunchedEffect(model.selectedId, list, api.base, agent, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (!list && model.selectedId != null) while (true) {
                awaitUiRead(model.pollExternalActivity())
                kotlinx.coroutines.delay(agentExternalPollDelay(model.externalRunning))
            }
        }
    }
    LaunchedEffect(model.asyncQuestion?.optString("request_id"), list) { if (!list && model.asyncQuestion != null) questionPanel = true }
    LaunchedEffect(api.base, list, agent, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (list) while (true) { awaitUiRead(model.pollSessionStates()); kotlinx.coroutines.delay(agentSessionPollDelay(model.hasKnownActivity)) }
        }
    }
    androidx.activity.compose.BackHandler(enabled = !list) { list = true }
    var queueDialog by remember { mutableStateOf(false) }
    var filesDialog by remember { mutableStateOf(false) }
    var attachMenu by remember { mutableStateOf(false) }
    var commands by remember { mutableStateOf(false) }
    var composerCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var commandBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var attachmentBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var commandPanelBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var attachmentPanelBounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var toolDetails by remember(model.selectedId) { mutableStateOf<String?>(null) }
    toolDetails?.let { key -> model.selectedId?.let { session ->
        ToolDetailsDialog(api, agent, session, key) { toolDetails = null }
    } }
    val context = LocalContext.current
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val imagePicker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(8)
        ) { uris ->
            model.upload(uris, true)
        }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { saved ->
        val photo = cameraPath?.let { java.io.File(it) }
        cameraPath = null
        if (photo != null) {
            if (saved && photo.length() > 0) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context, "${context.packageName}.hermes.files", photo,
                )
                model.upload(uri, true) { photo.delete() }
            } else photo.delete()
        }
    }
    val filePicker =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { model.upload(it, false) }
        }
    val scroll = rememberLazyListState()
    // LazyColumn evaluates its content later, potentially after history/SSE updates.
    // Capture the row and key together; never index keys using a newer model list.
    val messageRows = model.messages
    val messageItems = remember(messageRows) { chatMessageItems(messageRows) }
    val sessionsScroll = model.sessionsListState
    val searchingSessions = filter.isNotBlank()
    LaunchedEffect(filter) { model.searchSessions(filter) }
    val filteredSessions = if (searchingSessions) model.sessionSearchResults else model.orderedSessions
    val sessionsDragged by sessionsScroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(sessionsDragged) { if (sessionsDragged) model.sessionsListTouched = true }
    var latestRequest by remember(model.selectedId, list) { mutableStateOf(0L) }
    var followLatest by rememberChatFollowing(
        scroll, model.selectedId, list,
        model.messages.isNotEmpty() || model.pendingText.isNotEmpty(), model.scrollToLatestRequest, latestRequest,
    )
    val historyUiScope = rememberCoroutineScope()
    fun loadEarlier() = historyUiScope.launch {
        followLatest = false
        val session = model.selectedId
        val anchor = scroll.layoutInfo.visibleItemsInfo.firstOrNull { it.key != "history-start" }
        model.loadEarlierHistory().join()
        if (session == model.selectedId && anchor != null) {
            withFrameNanos { }
            val index = chatMessageItems(model.messages).indexOfFirst { it.key == anchor.key }
            if (index >= 0) scroll.scrollToItem(index + 1, (-anchor.offset).coerceAtLeast(0))
        }
    }
    val historyDragged by scroll.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(model.selectedId, list, historyDragged) {
        if (!list && historyDragged) {
            snapshotFlow { scroll.firstVisibleItemIndex }.collect { index ->
                if (index == 0 && model.historyHasMore && !model.historyLoadingEarlier) {
                    loadEarlier().join()
                }
            }
        }
    }
    fun createChat() {
        if (agent == "codex") { createCodex = true; model.fetchProjects() }
        else model.newSession(newHermesChatName(), { list = false })
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
            "/compact", "/compress" -> model.compact()
            "/status" -> model.reconnect()
            "/providers" -> {
                providers = true
                model.fetchProviders()
            }
            "/account", "/workspace" -> {
                if (agent == "codex") { accountsPanel = true; model.fetchAccounts() }
            }
            else -> model.error = "支持 /new /model /sessions /stop /status /providers" + if (agent == "codex") " /compact" else ""
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (list) "$agentName 会话" else model.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    // 本地已有数据、正在同步最新历史：标题后的小型加载指示
                    if (model.recovering || !list && model.historyLoading && model.messages.isNotEmpty())
                        CircularProgressIndicator(Modifier.padding(start = 8.dp).size(14.dp), strokeWidth = 2.dp)
                }
                if (list) Text(
                    model.runtime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
            if (list && agent == "codex") IconButton(
                onClick = { model.toggleGroupByProject() }, modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (model.groupByProject) Icons.Outlined.Subject else Icons.Outlined.FormatListBulleted,
                    "项目/最近切换"
                )
            }
            IconButton(
                onClick = {
                    list = !list
                    model.refresh()
                }, modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, "会话列表")
            }
            IconButton(onClick = { createChat() }, enabled = !model.submitting, modifier = Modifier.size(40.dp).offset(x = 2.dp)) { Icon(Icons.Outlined.Add, "新建会话") }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Outlined.MoreVert, "$agentName 菜单") }
                DropdownMenu(menu, { menu = false }) {
                    if (!list && model.canHandoverSession) DropdownMenuItem(
                        text = { Text("放弃写入权") },
                        onClick = {
                            menu = false
                            handoverSession = obj("id" to model.selectedId, "title" to model.title)
                        },
                    )
                    if (agent == "codex") DropdownMenuItem(text = { Text("账户与工作空间") }, onClick = { menu = false; accountsPanel = true; model.fetchAccounts() })
                    DropdownMenuItem(text = { Text("标题生成模型") }, onClick = { menu = false; titleModelDialog = true; model.fetchTitleModel() })
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
            }
        }
        if (agent == "codex") CodexUsage(model, compact = true) { accountsPanel = true; model.fetchAccounts() }
        if (!list && model.selectedId != null) AgentContextInfo(model.contextInfo,
            model.runtime + if (model.sessionEffort.isNotBlank()) " · ${effortLabel(model.sessionEffort)}思考" else "",
            onClick = { models = true; model.fetchModels() })
        if (!list && model.error == null && model.canTakeover) model.writeAccessMessage?.let { message ->
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
                Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { takeover = true }) { Text("中断并接管") }
            }
        }
        model.error?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    FlowRow {
                        TextButton(onClick = { model.reconnect() }) { Text("重新连接") }
                        if (model.hasPendingSubmission)
                            TextButton(onClick = { pendingInfo = true; model.reconcilePending() }) { Text("核对发送结果") }
                        if (model.canTakeover) TextButton(onClick = { takeover = true }) { Text("中断并接管") }
                        TextButton(onClick = { model.error = null }) { Text("收起") }
                    }
                }
            }
        }
        if (!list) model.controlMessage?.let { Text(it, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall) }
        if (!list && model.runId != null && !model.runStateVerified) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("任务状态尚未确认，计时已暂停", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.reconnect() }) { Text("核对状态") }
            }
        }
        if (model.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (list) {
            OutlinedTextField(
                filter,
                { filter = it },
                label = { Text("搜索会话") },
                placeholder = { Text("搜索标题和历史消息") },
                trailingIcon = {
                    if (filter.isNotEmpty()) IconButton(onClick = { filter = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = "清空搜索")
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            )
            if (searchingSessions && model.sessionSearchLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (searchingSessions && model.sessionSearchError != null) {
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(model.sessionSearchError!!, Modifier.weight(1f), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.searchSessions(filter) }) { Text("重试") }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().padding(end = 8.dp),
                state = sessionsScroll,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (searchingSessions && filteredSessions.isEmpty() && !model.sessionSearchLoading && model.sessionSearchError == null)
                    item { Text("没有匹配的会话或历史消息") }
                if (!searchingSessions && model.sessions.isEmpty() && !model.loading)
                    item { Text("暂无会话。点击 + 开始与 $agentName 对话。") }
                val renderSession: @Composable (JSONObject) -> Unit = { session ->
                    val latestActivity = parseMessageTimestamp(session.opt("last_active"))
                        ?.let(::formatMessageTimestamp)
                    Card(
                        onClick = {
                            model.select(session)
                            list = false
                        },
                        modifier = Modifier.fillMaxWidth().alpha(if (session.optBoolean("archived")) 0.55f else 1f),
                    ) {
                        Column(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Row(verticalAlignment = Alignment.Top) {
                                Column(
                                    Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(5.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (model.isSessionPinned(session)) {
                                            Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                                            Spacer(Modifier.width(5.dp))
                                        }
                                        Text(
                                            session.optString("title").ifBlank { "未命名会话" },
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 2,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        )
                                        model.sessionActivity(session)?.let { activity ->
                                            Surface(modifier = Modifier.clickable(enabled = activity == "待核对") { pendingInfo = true }, color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                                                Text(activity, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                    Text(
                                        if (searchingSessions && session.optString("search_snippet").isNotBlank()) session.optString("search_snippet")
                                        else session.optString("latest_user_message", session.optString("preview")),
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    )
                                }
                                var actions by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { actions = true }) { Icon(Icons.Outlined.MoreVert, "会话操作") }
                                    DropdownMenu(actions, { actions = false }) {
                                        DropdownMenuItem(
                                            text = { Text("会话详情") },
                                            leadingIcon = { Icon(Icons.Outlined.Info, null, Modifier.size(18.dp)) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            onClick = { actions = false; detailsChat = session },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("复制会话 ID") },
                                            leadingIcon = { Icon(Icons.Outlined.ContentCopy, null, Modifier.size(18.dp)) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            onClick = {
                                                actions = false
                                                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                                    .setPrimaryClip(android.content.ClipData.newPlainText("会话 ID", session.getString("id")))
                                                android.widget.Toast.makeText(context, "已复制会话 ID", android.widget.Toast.LENGTH_SHORT).show()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (model.isSessionPinned(session)) "取消置顶" else "置顶会话") },
                                            leadingIcon = { Icon(Icons.Outlined.PushPin, null, Modifier.size(18.dp)) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            enabled = session.getString("id") !in model.pinningSessions,
                                            onClick = { actions = false; model.toggleSessionPin(session) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("修改名称") },
                                            leadingIcon = { Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            onClick = { actions = false; renameChat = session },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("移动到项目") },
                                            leadingIcon = { Icon(Icons.Outlined.DriveFileMove, null, Modifier.size(18.dp)) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            onClick = { actions = false; moveChat = session },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(if (session.optBoolean("archived")) "取消存档" else "存档会话") },
                                            leadingIcon = { Icon(Icons.Outlined.Archive, null, Modifier.size(18.dp)) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            onClick = { actions = false; model.toggleSessionArchive(session) },
                                        )
                                        if (model.hasPendingFor(session.getString("id"))) DropdownMenuItem(
                                            text = { Text("核对上次提交") },
                                            leadingIcon = { Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp)) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            onClick = { actions = false; pendingInfo = true },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("删除会话", color = MaterialTheme.colorScheme.error) },
                                            leadingIcon = { Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                            onClick = { actions = false; deleteChat = session },
                                        )
                                    }
                                }
                            }
                            if (agent == "codex") {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiddleEllipsisText(
                                        session.optString("cwd").ifBlank { "Codex 会话" },
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    latestActivity?.let {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${session.optString("source")} · ${session.optInt("message_count")} 条消息",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    latestActivity?.let {
                                        Spacer(Modifier.width(8.dp))
                                        Text(it, style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        // 卡片最底部的上下文占用进度条：context_used/context_window 由服务端提供
                        // （codex 取自 rollout token_count；hermes 走懒加载批量接口，无数据默认 1M 窗口）。
                        val contextUsed = session.optLong("context_used", 0)
                        val contextWindow = session.optLong("context_window", 0)
                        if (contextUsed > 0 && contextWindow > 0) {
                            // 与 AgentContextInfo 的会话上下文条同款（默认轨道色，≥90% 变红）
                            LinearProgressIndicator(
                                progress = { (contextUsed.toDouble() / contextWindow).coerceIn(0.02, 1.0).toFloat() },
                                modifier = Modifier.fillMaxWidth().height(2.dp),
                                drawStopIndicator = {},
                                color = if (contextUsed.toDouble() / contextWindow >= .9) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                val sessionSections = if (agent == "codex" && !searchingSessions && model.groupByProject) groupCodexSessions(filteredSessions, model::isSessionPinned) else null
                if (sessionSections == null) {
                    items(
                        filteredSessions,
                        key = { it.getString("id") },
                    ) { session -> renderSession(session) }
                } else {
                    sessionSections.forEach { group ->
                        val collapsed = model.isGroupCollapsed(group.name)
                        item(key = "group:" + group.name) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { model.toggleSessionGroup(group.name) }
                                    .padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore,
                                    contentDescription = null,
                                    Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    group.name,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text("${group.sessions.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (!collapsed) {
                            items(group.sessions, key = { it.getString("id") }) { session -> renderSession(session) }
                        }
                    }
                }
                if (searchingSessions && model.sessionSearchHasMore)
                    item { TextButton(onClick = { model.moreSearchSessions() }, enabled = !model.sessionSearchLoading) { Text("加载更多搜索结果") } }
                if (!searchingSessions && model.hasMore)
                    item { TextButton(onClick = { model.moreSessions() }) { Text("加载更多会话") } }
            }
            SessionListScrollbar(sessionsScroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(4.dp).padding(vertical = 12.dp))
            }
        } else {
            model.historyCacheStatus?.let { label ->
                Text(label, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            if (model.messages.isEmpty() && model.historyLoading) {
                // 首次加载且本地无数据：对话区域中央的大旋转加载动画，替代一片空白
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(42.dp), strokeWidth = 3.5.dp)
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = scroll,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "history-start") {
                    if (model.historyHasMore || model.historyLoadingEarlier) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(enabled = !model.historyLoadingEarlier, onClick = {
                                loadEarlier()
                            }) { Text(if (model.historyLoadingEarlier) "正在加载更早消息…" else "加载更早消息") }
                        }
                    }
                }
                items(messageItems, key = { it.key }) { item ->
                    val message = item.message
                    if (message.role == "system" && message.nativeTurnId == "interaction") {
                        InteractionCard(message, agentName)
                    } else if (message.role == "system") {
                        Row(Modifier.fillMaxWidth().then(if (message.nativeTurnId == "toolSummary") Modifier.clickable { toolDetails = message.serverId.toString() } else Modifier)
                            .padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (message.nativeTurnId == "toolSummary") Icons.Outlined.Build else Icons.Outlined.CheckCircle,
                                null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(6.dp))
                            Text(message.text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(formatMessageTimestamp(message.timestamp), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f))
                        }
                    } else {
                    MessageBubble(message.role, message.text, message.attachments, api, model.files, agentName, message.delivery, message.timestamp,
                        narration = message.narration || message.localKey?.startsWith("narration-") == true, narrationTexts = model.narrationTexts,
                        forkDisabledReason = when {
                            !model.supportsMessageActions -> "当前连接尚未支持，请重新连接刷新或更新该设备服务端"
                            message.serverId <= 0 -> "消息尚未绑定已保存记录，请刷新历史"
                            model.messageActionBusy -> "正在处理消息操作"
                            else -> null
                        },
                        editDisabledReason = when {
                            !model.supportsMessageActions -> "当前连接尚未支持，请重新连接刷新或更新该设备服务端"
                            message.serverId <= 0 -> "消息尚未绑定已保存记录，请刷新历史"
                            !message.editable -> "仅可编辑该轮最初的用户消息"
                            model.hasExecution || model.runId != null -> "请等待当前会话执行结束"
                            model.submitting || model.hasPendingSubmission -> "请先核对消息发送状态"
                            model.messageActionBusy -> "正在处理消息操作"
                            else -> null
                        },
                        onFork = if (model.supportsMessageActions && message.serverId > 0 && !model.messageActionBusy) ({ model.forkMessage(message) { list = false } }) else null,
                        onEdit = if (message.role == "user" && message.serverId > 0 && message.editable && model.canEditMessages) ({
                            editTarget = model.selectedId!! to message; editText = message.text
                            editBoundary = messageEditBoundary(model.messages)
                            editAttachments = presentHermesMessage(message.text, message.attachments, model.files, message.role).files
                        }) else null)
                    }
                }
                if (model.runId != null && model.visiblePendingText.isNotBlank()) item {
                    MessageBubble("assistant", model.visiblePendingText, api = api, availableFiles = model.files,
                        agentName = agentName, timestamp = model.pendingTextTimestamp, narrationTexts = model.narrationTexts)
                }
                if (model.hasExecution) {
                    item {
                        var showTools by remember(model.executionKey) { mutableStateOf(false) }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        model.executionState,
                                        Modifier.weight(1f),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    if (!model.requestingCompaction && (model.runId != null || agent == "hermes" && model.externalRunning))
                                        TextButton(onClick = { stop = true }, enabled = !model.stopping) { Text(if (model.stopping) "正在停止" else "停止") }
                                    else if (!model.requestingCompaction && model.canTakeover) TextButton(onClick = { takeover = true }) { Text("中断并接管") }
                                }
                                if (!model.requestingCompaction) RunTimers(model.executionKey, model.executionTiming,
                                    responseLabel = if (model.runId == null) "距上次已保存响应" else "距上次响应", compacting = model.executionCompacting)
                                if (model.executionCompacting) {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                                    Text(if (model.requestingCompaction) "正在等待服务端确认" else "正在整理上下文，完成后可继续对话", Modifier.padding(top = 8.dp),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (!model.executionCompacting && model.executionEvents.isNotEmpty())
                                    TextButton(onClick = { showTools = !showTools }) {
                                        // 显示本 run 收到的工具/进度事件总数（events 列表只保留最近 30 条，直接用 size 会一直显示截断后的值）
                                        Text("工具与进度 · ${model.executionEventCount}")
                                    }
                                if (showTools && !model.executionCompacting)
                                    model.executionEvents.forEach {
                                        ToolProgressRow(it.text,
                                            if (agent == "codex") compactCodexToolPreview(it.detail) else it.detail,
                                            it.type)
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
                                Text("$agentName 等待你的决定", style = MaterialTheme.typography.titleSmall)
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
                                if (agent == "codex" && approval.optString("kind") == "user_input") {
                                    CodexQuestions(approval) { model.answerQuestions(it) }
                                } else Row {
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
            if (!followLatest && scroll.canScrollForward) SmallFloatingActionButton(
                onClick = {
                    latestRequest++
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) { Icon(Icons.Outlined.ArrowDownward, "回到最新消息") }
            }
            if (model.uploading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("上传附件中…", style = MaterialTheme.typography.labelSmall)
            }
            if (model.asyncQuestion != null) TextButton(onClick = { questionPanel = true }, modifier = Modifier.fillMaxWidth()) { Text("有问题需要你回答 · 查看选项") }
            if (model.messageQueue.visible.isNotEmpty()) TextButton(
                onClick = { queueDialog = true }, modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.PlaylistPlay, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text("待发送 · ${model.messageQueue.visible.size} 条 · ${if (model.messageQueue.paused) "已暂停" else "任务结束后发送"}")
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
                            (listOf(
                                    "新建会话" to "/new",
                                    "会话历史" to "/sessions",
                                    "状态与重连" to "/status",
                                    "停止任务" to "/stop",
                                ) + listOf("压缩上下文" to "/compact") + if (agent == "codex") listOf("账户与工作空间" to "/account") else emptyList())
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
                                text = { Text("选择图片（可多选）") },
                                enabled = model.pendingFiles.size < 8,
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
                                text = { Text("拍照") },
                                enabled = model.pendingFiles.size < 8,
                                onClick = {
                                    attachMenu = false
                                    try {
                                        val directory = java.io.File(context.cacheDir, "chat-camera").apply { mkdirs() }
                                        val photo = java.io.File.createTempFile("photo-", ".jpg", directory)
                                        cameraPath = photo.absolutePath
                                        val uri = androidx.core.content.FileProvider.getUriForFile(
                                            context, "${context.packageName}.hermes.files", photo,
                                        )
                                        camera.launch(uri)
                                    } catch (e: Exception) {
                                        cameraPath?.let { java.io.File(it).delete() }
                                        cameraPath = null
                                        android.widget.Toast.makeText(context, "无法打开相机：${e.message ?: "请检查是否安装相机应用"}", android.widget.Toast.LENGTH_LONG).show()
                                    }
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
                        Box(
                            modifier = Modifier.size(40.dp).focusProperties { canFocus = false }
                                .combinedClickable(
                                    enabled = canSend || model.runId != null,
                                    onLongClickLabel = "加入消息队列",
                                    onLongClick = { if (canSend && model.enqueueDraft()) queueDialog = true else if (!canSend) queueDialog = true },
                                    onClick = {
                                        if (model.runId != null && !canSend) stop = true
                                        else if (model.draft.trim().startsWith("/")) {
                                            command(model.draft); model.draft = ""
                                        } else model.send()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            ComposerActionGlyph(
                                stopping = model.runId != null && !canSend,
                                tint = if (canSend || model.runId != null) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
    if (queueDialog) MessageQueueDialog(model.messageQueue) { queueDialog = false }
    detailsChat?.let { chat ->
        SessionDetailsDialog(api, agent, chat) { detailsChat = null }
    }
    editTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!model.messageActionBusy) editTarget = null },
            title = { Text("编辑消息") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("发送后将撤回这条消息及后续对话，再发送修改后的内容。已执行的文件或程序操作不会撤销。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(editText, { editText = it }, modifier = Modifier.fillMaxWidth(),
                        minLines = 3, maxLines = 8, enabled = !model.messageActionBusy, label = { Text("消息内容") })
                    editAttachments.forEach { file ->
                        InputChip(selected = true, onClick = {}, label = { Text(file.optString("name"), maxLines = 1) },
                            trailingIcon = { IconButton(onClick = { editAttachments = editAttachments.filterNot { it.optString("id") == file.optString("id") } },
                                enabled = !model.messageActionBusy) { Icon(Icons.Outlined.Close, "移除附件", Modifier.size(18.dp)) } })
                    }
                    if (model.messageActionBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(enabled = model.canEditMessages && (editText.isNotBlank() || editAttachments.isNotEmpty()), onClick = {
                editBoundary?.let { boundary -> model.editMessage(target.second, editText.trim(), editAttachments, boundary) { editTarget = null } }
            }) { Text(if (model.messageActionBusy) "正在处理" else "撤回并重发") } },
            dismissButton = { TextButton(enabled = !model.messageActionBusy, onClick = { editTarget = null }) { Text("取消") } },
        )
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
    moveChat?.let { chat ->
        val currentProject = chat.optString("project_id")
        AlertDialog(
            onDismissRequest = { moveChat = null },
            title = { Text("移动到项目") },
            text = {
                Column {
                    if (model.projectsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (model.projects.isEmpty() && !model.projectsLoading) Text("没有可选项目", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        item {
                            Row(Modifier.fillMaxWidth().clickable(enabled = currentProject.isNotBlank()) {
                                model.moveSession(chat.getString("id"), "") { moveChat = null }
                            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("无项目", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (currentProject.isBlank()) Text("当前", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        items(model.projects, key = { it.optString("id") }) { project ->
                            val pid = project.optString("id")
                            Row(Modifier.fillMaxWidth().clickable(enabled = pid != currentProject) {
                                model.moveSession(chat.getString("id"), pid) { moveChat = null }
                            }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(project.optString("name"), style = MaterialTheme.typography.bodyMedium)
                                    Text(project.array("roots").objects().firstOrNull()?.optString("path") ?: "",
                                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                }
                                if (pid == currentProject) Text("当前", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { moveChat = null }) { Text("关闭") } },
        )
    }
    deleteChat?.let { chat ->
        ConfirmDialog("删除会话", "删除“${chat.optString("title") }”及其消息历史，无法撤销。附件按服务端清理规则保留。", { deleteChat = null }) {
            val selected = model.selectedId == chat.getString("id")
            model.deleteSession(chat.getString("id")) { if (selected) list = true }
            deleteChat = null
        }
    }
    handoverSession?.let { chat ->
        ConfirmDialog("放弃写入权", "释放手机端对“${chat.optString("title")}”的写入权。正在执行的任务会被中断；已执行的操作不会撤销，会话历史会保留。之后可在桌面端打开同一会话继续。", { handoverSession = null }) {
            model.handoverSession(chat.getString("id"))
            handoverSession = null
        }
    }
    if (stop)
        ConfirmDialog("停止 $agentName 任务", "请求在安全中断点停止，不会撤销已经执行的操作。", { stop = false }) {
            model.stop()
            stop = false
        }
    if (takeover) ConfirmDialog("中断并接管当前会话", "请求中断此会话在其他端的任务，已执行的操作不会撤销。确认原任务结束且会话可写入后，再由你发送保留的草稿。其他端已启动新任务时会取消接管。", { takeover = false }) {
        takeover = false; model.takeover()
    }
    if (models) HermesModelPicker(model) { models = false }
    if (providers) HermesProviderDialog(model) { providers = false }
    if (titleModelDialog) CodexTitleModelDialog(model) { titleModelDialog = false }
    if (accountsPanel) CodexAccountsDialog(model, { accountsPanel = false }) { list = true }
    if (questionPanel && model.asyncQuestion != null && !list) AlertDialog(
        onDismissRequest = { questionPanel = false }, title = { Text("需要你的回答") },
        text = { androidx.compose.foundation.rememberScrollState().let { state ->
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(state)) {
                CodexQuestions(model.asyncQuestion!!) { answers -> if (model.answerAsyncQuestion(model.asyncQuestion!!, answers)) questionPanel = false }
            }
        } },
        confirmButton = {}, dismissButton = { TextButton(onClick = { questionPanel = false }) { Text("稍后回答") } },
    )
    if (pendingInfo) AlertDialog(onDismissRequest = { pendingInfo = false }, title = { Text("上次提交未确认") },
        text = { Text("手机未收到某次提交的确认结果，此标记不代表电脑正在运行任务。可先核对会话历史；清除记录会恢复输入草稿，不会终止电脑任务。") },
        confirmButton = { TextButton(onClick = { model.reconcilePending(); pendingInfo = false }) { Text("重新核对") } },
        dismissButton = { Row { TextButton(onClick = { model.clearPendingRecord(); pendingInfo = false }) { Text("清除记录") }; TextButton(onClick = { pendingInfo = false }) { Text("关闭") } } })
    if (createCodex) CodexNewSessionDialog(api, model, { createCodex = false }) { options ->
        model.newSession(newHermesChatName(), { createCodex = false; list = false }, options)
    }
    if (agent == "codex") CodexLoginDialog(model)
    if (agent == "codex") CodexSwitchingDialog(model)
    if (filesDialog) HermesFilesDialog(api, model.files) { filesDialog = false }
    preview?.let { file ->
        MediaViewer(api, file, { preview = null }, { downloadHermesFile(context, api, file) })
    }
}

@Composable
private fun SessionListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    // Card heights vary, so visibleItemsInfo.size changes at item boundaries. Freeze
    // the visible-slot estimate while scrolling; total count still tracks list changes.
    var visibleSlots by remember(state) { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        visibleSlots = snapshotFlow {
            state.layoutInfo.let { it.totalItemsCount to it.visibleItemsInfo.size }
        }.first { (total, visible) -> visible > 0 && total > visible }.second
    }
    val thumbColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    Canvas(modifier) {
        val layout = state.layoutInfo
        val total = layout.totalItemsCount
        if (visibleSlots == 0 || layout.visibleItemsInfo.isEmpty() ||
            !state.canScrollBackward && !state.canScrollForward) return@Canvas
        val thumbHeight = (size.height * visibleSlots.toFloat() / total.coerceAtLeast(1))
            .coerceAtLeast(24.dp.toPx()).coerceAtMost(size.height)
        val first = layout.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
        val fraction = state.firstVisibleItemScrollOffset.toFloat() /
            ((first?.size ?: 1) + layout.mainAxisItemSpacing).coerceAtLeast(1)
        val lastStart = (total - visibleSlots.coerceAtLeast(1)).coerceAtLeast(1)
        val position = when {
            !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> ((state.firstVisibleItemIndex + fraction) / lastStart).coerceIn(0f, 1f)
        }
        drawRoundRect(thumbColor, Offset(0f, (size.height - thumbHeight) * position),
            Size(size.width, thumbHeight), CornerRadius(size.width / 2f))
    }
}

@Composable
private fun MiddleEllipsisText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val availableWidth = constraints.maxWidth
        val displayText = remember(text, availableWidth, style, textMeasurer) {
            if (availableWidth == Constraints.Infinity || textMeasurer.measure(text, style).size.width <= availableWidth) {
                text
            } else {
                var shortestFit = "…"
                var low = 0
                var high = text.length
                while (low <= high) {
                    val keep = (low + high) / 2
                    val prefixLength = (keep + 1) / 2
                    val candidate = text.take(prefixLength) + "…" + text.takeLast(keep - prefixLength)
                    if (textMeasurer.measure(candidate, style).size.width <= availableWidth) {
                        shortestFit = candidate
                        low = keep + 1
                    } else {
                        high = keep - 1
                    }
                }
                shortestFit
            }
        }
        Text(
            displayText,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
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
private fun RunTimers(runId: String?, timing: AgentRunTiming, responseLabel: String = "距上次响应", compacting: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember(runId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(runId, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
        }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            (if (compacting) "等待压缩结果" else if (timing.lastResponseAt == null) "等待首次响应" else responseLabel) to (timing.lastResponseAt ?: timing.startedAt),
            (if (compacting) "压缩已运行" else "任务已运行") to timing.startedAt,
        ).forEach { (label, since) ->
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatRunElapsed(since, now), style = MaterialTheme.typography.titleSmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun InteractionCard(message: HermesMessage, agentName: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("提问", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                message.interactions.forEachIndexed { index, entry ->
                    if (index > 0) {
                        HorizontalDivider(Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    val question = entry.optString("question")
                    val answer = entry.optString("answer")
                    val status = entry.optString("status")
                    val choices = entry.optJSONArray("choices")?.let { array ->
                        (0 until array.length()).map { array.optString(it) }
                    }.orEmpty()
                    if (question.isNotBlank())
                        Text(question, style = MaterialTheme.typography.bodyMedium)
                    if (choices.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            choices.forEach { choice ->
                                val picked = choice == answer
                                Surface(shape = MaterialTheme.shapes.small,
                                    color = if (picked) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceContainerHighest) {
                                    Text(choice, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (picked) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row {
                        Text("回答", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            answer.ifBlank { when (status) { "answered" -> "已回答"; "declined" -> "已取消"; else -> "未回答" } },
                            style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FlowRow(
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable () -> Unit,
) = androidx.compose.foundation.layout.FlowRow(horizontalArrangement = horizontalArrangement,
    verticalArrangement = verticalArrangement, content = { content() })

@Composable
private fun MessageBubble(
    role: String,
    text: String,
    attachments: List<JSONObject> = emptyList(),
    api: NativeApi? = null,
    availableFiles: List<JSONObject> = emptyList(),
    agentName: String = "Hermes",
    delivery: String? = null,
    timestamp: Long? = null,
    narration: Boolean = false,
    narrationTexts: List<String> = emptyList(),
    onFork: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    forkDisabledReason: String? = null,
    editDisabledReason: String? = null,
) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var actions by remember { mutableStateOf(false) }
    var bubbleOrigin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var menuPoint by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val openActions: (androidx.compose.ui.geometry.Offset) -> Unit = { windowPoint ->
        menuPoint = windowPoint - bubbleOrigin
        actions = true
    }
    val presentation = presentHermesMessage(text, attachments, availableFiles, role)
    val hasImages = presentation.files.any { hermesFileKind(it) == "图片" }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement =
            if (role == "user" && !hasImages)
                Arrangement.End else Arrangement.Start,
    ) {
        Box(Modifier.onGloballyPositioned { bubbleOrigin = it.localToWindow(androidx.compose.ui.geometry.Offset.Zero) }) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color =
                if (role == "user") MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = if (hasImages) 268.dp else 600.dp).bubbleTap(openActions),
        ) {
            SelectionContainer {
                Column(Modifier.padding(14.dp)) {
                    val time = formatMessageTimestamp(timestamp)
                    val inlineTime = presentation.text.isNotBlank() && presentation.files.isEmpty() &&
                        presentation.unavailable.isEmpty()
                    val metadata = listOfNotNull(delivery, time.takeIf { it.isNotBlank() }).joinToString(" · ")
                    if (presentation.text.isNotBlank()) HermesMarkdown(
                        if (role == "assistant") displayNarration(presentation.text, narration, narrationTexts) else presentation.text,
                        footer = if (inlineTime) metadata else "",
                        onBubbleTap = openActions,
                    )
                    if (!inlineTime) delivery?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
                    if (!inlineTime && time.isNotBlank()) Text(
                        time,
                        modifier = Modifier.align(Alignment.End).padding(top = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1,
                    )
                }
            }
        }
        // A zero-size anchor at the finger keeps Material's edge-aware menu placement:
        // right/below first, left or above when space is insufficient.
        Box(Modifier.offset { androidx.compose.ui.unit.IntOffset(menuPoint.x.toInt(), menuPoint.y.toInt()) }.size(0.dp)) {
        DropdownMenu(actions, { actions = false }) {
            DropdownMenuItem(text = { Text("复制") }, leadingIcon = { Icon(Icons.Outlined.ContentCopy, null) }, onClick = {
                actions = false
                val copied = presentation.text.ifBlank { presentation.files.joinToString("\n") { it.optString("name") } }
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("消息", copied))
                android.widget.Toast.makeText(context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
            })
            DropdownMenuItem(text = { Column { Text("分叉");
                if (onFork == null && forkDisabledReason != null) Text(forkDisabledReason, style = MaterialTheme.typography.labelSmall)
                else if (agentName == "Codex") Text("保留该消息所在的完整轮次", style = MaterialTheme.typography.labelSmall)
            } }, leadingIcon = { Icon(Icons.Outlined.CallSplit, null) }, enabled = onFork != null,
                onClick = { actions = false; onFork?.invoke() })
            if (role == "user") DropdownMenuItem(text = { Column { Text("编辑");
                if (onEdit == null && editDisabledReason != null) Text(editDisabledReason, style = MaterialTheme.typography.labelSmall)
            } }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, enabled = onEdit != null,
                onClick = { actions = false; onEdit?.invoke() })
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
    var effort by remember { mutableStateOf("") }
    var tier by remember { mutableStateOf("") }
    val currentProvider = if (global) model.modelOptions.optString("provider") else model.sessionProvider.ifBlank { model.modelOptions.optString("provider") }
    val currentModel = if (global) model.modelOptions.optString("model") else model.sessionModel.ifBlank { model.modelOptions.optString("model") }
    val currentEffort = if (global) model.modelOptions.cleanSetting("reasoning_effort") else model.sessionEffort
    val currentTier = if (global) model.modelOptions.cleanSetting("service_tier") else model.sessionTier
    // 会话详情里的 provider 由服务端注入权威值（state.db 的 model_config.provider）；
    // 这里只做 slug → 显示名的解析，供头部文案使用。高亮按 slug 精确匹配。
    val providerCatalog = model.modelOptions.array("providers").objects()
    val resolvedProvider = currentProvider
    val resolvedProviderName = providerCatalog.firstOrNull { it.optString("slug") == resolvedProvider }?.optString("name", displayProvider(resolvedProvider)) ?: displayProvider(resolvedProvider)
    LaunchedEffect(provider?.optString("slug"), global, resolvedProvider, currentModel) {
        selected = if (provider?.optString("slug") == resolvedProvider) currentModel.takeIf { it.isNotBlank() } else null
    }
    LaunchedEffect(selected, provider?.optString("slug"), global) {
        val isCurrent = selected == currentModel && provider?.optString("slug") == resolvedProvider
        val choices = reasoningChoices(model.agent, provider, selected)
        effort = if (isCurrent && currentEffort in choices) currentEffort else ""
        tier = if (isCurrent) currentTier else ""
        if (tier !in listOf("", "default", "standard", fastTier(provider, selected))) tier = ""
    }
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
                    "${if (global) "全局默认" else "当前会话"}：$resolvedProviderName · $currentModel",
                    style = MaterialTheme.typography.labelSmall,
                )
                LazyColumn(Modifier.heightIn(max = if (selected != null) 180.dp else 340.dp)) {
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
                                colors = CardDefaults.cardColors(containerColor = if (p.optString("slug") == resolvedProvider) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh),
                                border = if (p.optString("slug") == resolvedProvider) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        p.optString("name", p.optString("slug")),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "${p.array("models").length()} 个模型" +
                                            (if (p.optString("slug") == resolvedProvider) " · 当前使用"
                                            else if (p.optBoolean("is_current")) " · 全局默认"
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
                                            "model",
                                            item.optString("id", item.optString("name")),
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
                                if (provider!!.optString("slug") == resolvedProvider && name == currentModel)
                                    Text("当前使用", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            }
                        }

                    }
                }
                        if (selected != null) Column {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp))
                            val efforts = reasoningChoices(model.agent, provider, selected)
                            if (efforts.isNotEmpty()) {
                                Text("思考程度", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (listOf("") + efforts).forEach { value ->
                                        FilterChip(effort == value, { effort = value; model.modelWarning = null },
                                            label = { Text(effortLabel(value)) }, enabled = !model.modelSaving)
                                    }
                                }
                            } else Text("该模型未提供可选思考档位", style = MaterialTheme.typography.labelSmall)
                            if (model.agent == "codex") {
                                Text("速度", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(tier.isBlank(), { tier = "" }, label = { Text("默认") }, enabled = !model.modelSaving)
                                    FilterChip(tier in listOf("default", "standard"), { tier = "default" }, label = { Text("标准") }, enabled = !model.modelSaving)
                                    fastTier(provider, selected)?.let { fast ->
                                        FilterChip(tier == fast, { tier = fast }, label = { Text("快速") }, enabled = !model.modelSaving)
                                    }
                                }
                                if (fastTier(provider, selected) != null)
                                    Text("快速模式的可用性和用量以当前 Provider 为准", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("保存后用于下一轮消息", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                effort = effort,
                                tier = tier,
                            )
                        }
                    },
                    enabled = selected != null && !model.modelSaving,
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
                        if (model.agent == "codex") "新增或编辑 Responses API 兼容端点。已有密钥不会返回手机。"
                        else "新增或编辑 OpenAI / Responses / Anthropic 兼容端点。已有密钥不会返回手机。",
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
                    (if (model.agent == "codex") listOf("codex_responses" to "Responses API") else listOf(
                            "" to "自动识别",
                            "chat_completions" to "Chat Completions",
                            "codex_responses" to "Responses API",
                            "anthropic_messages" to "Anthropic Messages",
                        ))
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
