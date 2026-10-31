@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.chuckiehelper.mobile.nativeui

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject

fun parentPath(path: String): String {
    val p = path.trimEnd('/', '\\')
    val index = maxOf(p.lastIndexOf('/'), p.lastIndexOf('\\'))
    return if (index < 0) "" else p.substring(0, index + 1)
}
fun directoryPositionKey(path: String): String {
    if (path.isEmpty()) return "<drives>"
    val normalized = path.replace('\\', '/').let { if (it == "/") it else it.trimEnd('/') }
    return if (path.startsWith("\\\\") || Regex("^[a-zA-Z]:").containsMatchIn(path)) normalized.lowercase() else normalized
}

@Composable
private fun FileDirectionToggle(descending: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !descending, onClick = { onChange(false) },
            label = { Text("升序", style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.height(28.dp))
        FilterChip(selected = descending, onClick = { onChange(true) },
            label = { Text("降序", style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.height(28.dp))
    }
}
@Composable
fun FilesScreen(api: NativeApi, onError: (String) -> Unit, onClose: (() -> Unit)? = null, onCompose: (String) -> Unit) {
    var path by rememberSaveable { mutableStateOf("") }
    var inputPath by rememberSaveable { mutableStateOf("") }
    var editingPath by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf<List<JSONObject>?>(null) }
    var filesPath by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var operationTargets by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pending by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var document by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) }
    var media by remember { mutableStateOf<JSONObject?>(null) }
    var menuFile by remember { mutableStateOf<JSONObject?>(null) }
    var gitOperation by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val fileRowHeight = (52f * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    val viewPrefs = remember(context) { context.getSharedPreferences("file-view", Context.MODE_PRIVATE) }
    var viewSettings by remember {
        mutableStateOf(FileViewSettings(
            sort = runCatching { FileSort.valueOf(viewPrefs.getString("sort", "NAME") ?: "NAME") }.getOrDefault(FileSort.NAME),
            secondarySort = viewPrefs.getString("secondarySort", null)?.let { runCatching { FileSort.valueOf(it) }.getOrNull() },
            descending = viewPrefs.getBoolean("descending", false),
            secondaryDescending = viewPrefs.getBoolean("secondaryDescending", false),
            foldersFirst = viewPrefs.getBoolean("foldersFirst", true),
        ))
    }
    var viewDialog by remember { mutableStateOf(false) }
    var createKind by remember { mutableStateOf(false) }
    val visibleFiles = remember(files, viewSettings) { sortRemoteFiles(files.orEmpty(), viewSettings) }
    fun updateView(value: FileViewSettings) {
        viewSettings = value
        viewPrefs.edit().putString("sort", value.sort.name)
            .putString("secondarySort", value.secondarySort?.name)
            .putBoolean("descending", value.descending)
            .putBoolean("secondaryDescending", value.secondaryDescending)
            .putBoolean("foldersFirst", value.foldersFirst).apply()
    }
    var directoryPositions by rememberSaveable { mutableStateOf(hashMapOf<String, ArrayList<Int>>()) }
    val fileScroll = rememberSaveable(path, saver = LazyListState.Saver) {
        val saved = directoryPositions[directoryPositionKey(path)] ?: arrayListOf(0, 0)
        LazyListState(saved[0], saved[1])
    }
    DisposableEffect(path, fileScroll) {
        val currentPath = path
        onDispose { directoryPositions = HashMap(directoryPositions).apply { put(directoryPositionKey(currentPath), arrayListOf(fileScroll.firstVisibleItemIndex, fileScroll.firstVisibleItemScrollOffset)) } }
    }
    fun task(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "操作失败")
            } finally {
                busy = false
            }
        }
    }
    fun navigate(next: String) {
        directoryPositions = HashMap(directoryPositions).apply { put(directoryPositionKey(path), arrayListOf(fileScroll.firstVisibleItemIndex, fileScroll.firstVisibleItemScrollOffset)) }
        path = next
        inputPath = next
        selected = emptySet()
    }
    BackHandler(
        enabled =
            (selected.isNotEmpty() || path.isNotEmpty()) &&
                document == null &&
                media == null &&
                input == null &&
                pending == null
    ) {
        if (selected.isNotEmpty()) selected = emptySet()
        else navigate(parentPath(path))
    }
    LaunchedEffect(api, path, refresh) {
        val requestedPath = path
        files = null
        filesPath = null
        error = null
        try {
            files =
                api.json("/api/files/list" + (if (path.isEmpty()) "" else "?path=${q(path)}"))
                    .array("data")
                    .objects()
            filesPath = requestedPath
            error = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
    }
    fun download(file: JSONObject) {
        try {
            val request =
                DownloadManager.Request(
                        Uri.parse(
                            api.base + "/api/files/download?path=${q(file.getString("path"))}"
                        )
                    )
                    .addRequestHeader("Cookie", api.cookie())
                    .setTitle(file.getString("name"))
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
            if (android.os.Build.VERSION.SDK_INT >= 29)
                request.setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    file.getString("name"),
                )
            else
                request.setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_DOWNLOADS,
                    file.getString("name"),
                )
            (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(request)
            onError("下载已开始")
        } catch (e: Exception) {
            onError(e.message ?: "下载失败")
        }
    }
    fun edit(file: JSONObject, hex: Boolean) {
        task {
            val target = file.getString("path")
            val text =
                if (!hex) api.json("/api/files/read?path=${q(target)}").optString("data")
                else
                    api.response(api.request("/api/files/read?binary=true&path=${q(target)}"))
                        .use { res ->
                            if (res.code == 401) throw LoginRequired()
                            if (!res.isSuccessful)
                                throw java.io.IOException("读取失败 HTTP ${res.code}")
                            withContext(Dispatchers.IO) {
                                val out = java.io.ByteArrayOutputStream()
                                res.body!!.byteStream().use { source ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        val n = source.read(buffer)
                                        if (n < 0) break
                                        out.write(buffer, 0, n)
                                        require(out.size() <= 2 * 1024 * 1024) { "十六进制编辑最大支持 2 MB" }
                                    }
                                }
                                out.toByteArray().joinToString(" ") {
                                    "%02X".format(it.toInt() and 255)
                                }
                            }
                        }
            document = Triple(target, text, hex)
        }
    }
    fun itemsBody(targets: List<JSONObject>) =
        JSONArray().apply {
            targets.forEach {
                put(
                    obj(
                        "path" to it.optString("path"),
                        "isDirectory" to it.optBoolean("isDirectory"),
                    )
                )
            }
        }
    fun operation(name: String, targets: List<JSONObject>) {
        if (targets.isEmpty() || busy) return
        operationTargets = targets.toList()
        menuFile = null
        val file = targets.first()
        val target = file.optString("path")
        when (name) {
            "Git 状态", "Git 提交并推送", "Git 推送", "Git 拉取" -> gitOperation = target to when (name) {
                "Git 状态" -> "status"; "Git 提交并推送" -> "commit-push"; "Git 拉取" -> "pull"; else -> "push"
            }
            "复制路径" -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("文件路径", "\"$target\""))
                onError("路径已复制")
            }
            "Compose 管理" -> onCompose(target)
            "下载" -> download(file)
            "文本编辑" -> edit(file, false)
            "十六进制" -> edit(file, true)
            "删除" ->
                pending =
                    "删除 ${targets.size} 个项目？此操作无法撤销。" to
                        {
                            task {
                                api.json(
                                    "/api/files/delete-batch",
                                    obj("items" to itemsBody(targets)),
                                )
                                selected = emptySet()
                                refresh++
                            }
                        }
            else ->
                input =
                    Triple(
                        name,
                        target,
                        when (name) {
                            "重命名" -> target
                            "修改卷标" -> file.optString("name")
                            "压缩" -> target + ".zip"
                            else -> path
                        },
                    )
        }
    }
    val uploader =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val destination = path
            if (uris.isNotEmpty())
                task {
                    for (uri in uris) {
                        val name =
                            context.contentResolver
                                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                ?.use { if (it.moveToFirst()) it.getString(0) else null }
                                ?: "upload.bin"
                        val content =
                            object : RequestBody() {
                                override fun contentType() =
                                    "application/octet-stream".toMediaType()

                                override fun writeTo(sink: BufferedSink) {
                                    context.contentResolver.openInputStream(uri)!!.use { source ->
                                        val buffer = ByteArray(65536)
                                        while (true) {
                                            val n = source.read(buffer)
                                            if (n < 0) break
                                            sink.write(buffer, 0, n)
                                        }
                                    }
                                }
                            }
                        val body =
                            MultipartBody.Builder()
                                .setType(MultipartBody.FORM)
                                .addFormDataPart("file", name, content)
                                .build()
                        val request =
                            Request.Builder()
                                .url(api.base + "/api/files/upload?path=${q(destination)}")
                                .header("Cookie", api.cookie())
                                .post(body)
                                .build()
                        api.json(request)
                    }
                    refresh++
                    onError("上传完成")
                }
        }
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                if (onClose != null) IconButton(onClick = { if (selected.isNotEmpty()) selected = emptySet() else onClose() }) { Icon(Icons.Outlined.ArrowBack, "返回") }
                Column(Modifier.weight(1f).clickable { inputPath = path; editingPath = true }.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(path.ifBlank { "服务器磁盘" }, style = MaterialTheme.typography.titleMedium,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text(if (files == null || filesPath != path) "正在读取…" else
                        "文件夹 ${visibleFiles.count { it.optBoolean("isDirectory") }} · 文件 ${visibleFiles.count { !it.optBoolean("isDirectory") }}" +
                            if (selected.isNotEmpty()) " · 已选 ${selected.size}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { viewDialog = true }) { Icon(Icons.Outlined.Settings, "视图设置") }
            }
        }
        HorizontalDivider()
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Box(Modifier.weight(1f).fillMaxWidth()) {
        if (files == null || filesPath != path) ErrorPane(error) { refresh++ }
        else
            LazyColumn(
                state = fileScroll,
                contentPadding = PaddingValues(vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                if (path.isNotEmpty()) item(key = "parent-directory") {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                        if (selected.isNotEmpty()) selected = emptySet() else navigate(parentPath(path))
                    }.padding(horizontal = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Folder, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("..", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (visibleFiles.isEmpty()) item { Text("此目录为空", Modifier.padding(24.dp)) }
                items(visibleFiles, key = { it.optString("path") }) { file ->
                    val target = file.optString("path")
                    val isDir = file.optBoolean("isDirectory")
                    Surface(Modifier.fillMaxWidth(), color = if (target in selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                        val isDiskRow = file.has("totalBytes")
                        Row(
                            Modifier.fillMaxWidth().height(if (isDiskRow) fileRowHeight + 12.dp else fileRowHeight)
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty() && !isDiskRow)
                                            selected =
                                                if (target in selected) selected - target
                                                else selected + target
                                        else if (isDir) navigate(target)
                                        else if (isComposeFile(file.optString("name")))
                                            onCompose(target)
                                        else if (
                                            file
                                                .optString("name")
                                                .substringAfterLast('.')
                                                .lowercase() in
                                                listOf(
                                                    "png",
                                                    "jpg",
                                                    "jpeg",
                                                    "gif",
                                                    "webp",
                                                    "bmp",
                                                    "mp4",
                                                    "wmv",
                                                    "mkv",
                                                    "mov",
                                                    "avi",
                                                    "webm",
                                                    "mp3",
                                                    "flac",
                                                    "wav",
                                                    "m4v",
                                                )
                                        )
                                            media = file
                                        else edit(file, false)
                                    },
                                    onLongClick = if (!isDiskRow && path.isNotEmpty()) { { selected = selected + target } } else null,
                                )
                                .padding(start = 10.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            if (selected.isNotEmpty() && !isDiskRow)
                                Checkbox(
                                    target in selected,
                                    onCheckedChange = {
                                        selected = if (it) selected + target else selected - target
                                    },
                                )
                            else
                                Icon(
                                    fileIcon(file),
                                    null,
                                    Modifier.size(26.dp),
                                    tint =
                                        if (isDir) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondary,
                                )
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(
                                    file.optString("name"),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                )
                                val modified = if (isDiskRow) "" else formatFileModifiedTime(file.optString("modified"))
                                Text(
                                    (if (isDir)
                                        if (isDiskRow) {
                                            val total = file.optDouble("totalBytes")
                                            val usedPct = if (total > 0) (((total - file.optDouble("freeBytes")) / total) * 100).toInt() else 0
                                            "已用 $usedPct% · 可用 ${bytes(file.optDouble("freeBytes"))} / ${bytes(total)}"
                                        } else "文件夹"
                                    else bytes(file.optDouble("size"))) +
                                        if (modified.isNotEmpty()) " · $modified" else "",
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (isDiskRow) {
                                    val total = file.optDouble("totalBytes")
                                    val usedFraction =
                                        if (total > 0) (((total - file.optDouble("freeBytes")) / total).toFloat()).coerceIn(0f, 1f) else 0f
                                    LinearProgressIndicator(
                                        progress = { usedFraction },
                                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp).height(4.dp),
                                        color = when {
                                            usedFraction >= 0.9f -> MaterialTheme.colorScheme.error
                                            usedFraction >= 0.75f -> MaterialTheme.colorScheme.tertiary
                                            else -> MaterialTheme.colorScheme.primary
                                        },
                                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                        gapSize = 0.dp,
                                        drawStopIndicator = {},
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { menuFile = file }) {
                                    Icon(Icons.Outlined.MoreVert, "操作")
                                }
                                DropdownMenu(
                                    expanded = menuFile?.optString("path") == target,
                                    onDismissRequest = { menuFile = null },
                                ) {
                                    val isDisk = file.has("totalBytes")
                                    // 磁盘行只支持查看路径与改卷标：重命名即修改卷标，不提供删除。
                                    val actions = if (isDisk) mutableListOf("复制路径", "修改卷标")
                                    else mutableListOf("复制路径", "重命名", "复制", "移动", "压缩")
                                    if (!isDisk && file.optString("name").equals(".git", ignoreCase = true))
                                        actions.addAll(0, listOf("Git 状态", "Git 提交并推送", "Git 推送", "Git 拉取"))
                                    if (
                                        !isDir &&
                                            !isDisk &&
                                                file
                                                    .optString("name")
                                                    .substringAfterLast('.')
                                                    .lowercase() in listOf("yml", "yaml")
                                    )
                                        actions.add(0, "Compose 管理")
                                    if (!isDir && !isDisk) actions.addAll(listOf("下载", "文本编辑", "十六进制"))
                                    if (
                                        file
                                            .optString("name")
                                            .substringAfterLast('.')
                                            .lowercase() in listOf("zip", "rar", "7z", "gz", "tar")
                                    )
                                        actions.add("解压")
                                    if (!isDisk) actions.add("删除")
                                    actions.forEach { name ->
                                        DropdownMenuItem(
                                            text = { Text(name, color = if (name == "删除") MaterialTheme.colorScheme.error else LocalContentColor.current) },
                                            leadingIcon = { Icon(fileActionIcon(name), null, Modifier.size(18.dp),
                                                tint = if (name == "删除") MaterialTheme.colorScheme.error else LocalContentColor.current) },
                                            onClick = { operation(name, listOf(file)) },
                                            modifier = Modifier.heightIn(min = 40.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

        }
        HorizontalDivider()
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                if (selected.isEmpty()) {
                    FileToolbarButton("磁盘", Icons.Outlined.Storage, !busy, Modifier.weight(1f)) { navigate("") }
                    FileToolbarButton("上级", Icons.Outlined.ArrowUpward, !busy && path.isNotEmpty(), Modifier.weight(1f)) { navigate(parentPath(path)) }
                    FileToolbarButton("新建", Icons.Outlined.CreateNewFolder, !busy && path.isNotBlank(), Modifier.weight(1f)) { createKind = true }
                    FileToolbarButton("上传", Icons.Outlined.UploadFile, !busy && path.isNotBlank(), Modifier.weight(1f)) { uploader.launch(arrayOf("*/*")) }
                    FileToolbarButton("刷新", Icons.Outlined.Refresh, !busy, Modifier.weight(1f)) { refresh++ }
                } else {
                    FileToolbarButton("取消", Icons.Outlined.Close, !busy, Modifier.weight(1f)) { selected = emptySet() }
                    listOf(
                        "复制" to Icons.Outlined.ContentCopy,
                        "移动" to Icons.Outlined.DriveFileMove,
                        "压缩" to Icons.Outlined.FolderZip,
                        "删除" to Icons.Outlined.Delete,
                    ).forEach { (name, icon) ->
                        FileToolbarButton(name, icon, !busy, Modifier.weight(1f)) {
                            operation(name, files.orEmpty().filter { it.optString("path") in selected })
                        }
                    }
                }
            }
        }
    }
    if (editingPath) InputDialog("前往目录", "服务器上的目录路径", inputPath, { editingPath = false }) {
        editingPath = false; navigate(it)
    }
    if (createKind) AlertDialog(
        onDismissRequest = { createKind = false },
        title = { Text("新建") },
        text = { Text("选择要创建的类型") },
        confirmButton = {
            TextButton(onClick = { createKind = false; input = Triple("新建文件夹", "", path) }) { Text("文件夹") }
        },
        dismissButton = {
            TextButton(onClick = { createKind = false; input = Triple("新建文件", "", path) }) { Text("文件") }
        },
    )
    if (viewDialog) AlertDialog(onDismissRequest = { viewDialog = false }, title = { Text("视图设置") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("排序方式", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    FileDirectionToggle(viewSettings.descending) { updateView(viewSettings.copy(descending = it)) }
                }
                FlowRowCompat {
                    FileSort.entries.forEach { sort ->
                        FilterChip(selected = viewSettings.sort == sort,
                            onClick = { updateView(viewSettings.copy(sort = sort)) },
                            label = { Text(sort.label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.height(30.dp))
                    }
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("次要排序", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    FileDirectionToggle(viewSettings.secondaryDescending) { updateView(viewSettings.copy(secondaryDescending = it)) }
                }
                FlowRowCompat {
                    FilterChip(selected = viewSettings.secondarySort == null,
                        onClick = { updateView(viewSettings.copy(secondarySort = null)) },
                        label = { Text("无", style = MaterialTheme.typography.labelMedium) },
                        modifier = Modifier.height(30.dp))
                    FileSort.entries.forEach { sort ->
                        FilterChip(selected = viewSettings.secondarySort == sort,
                            onClick = { updateView(viewSettings.copy(secondarySort = sort)) },
                            label = { Text(sort.label, style = MaterialTheme.typography.labelMedium) },
                            modifier = Modifier.height(30.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { updateView(viewSettings.copy(foldersFirst = !viewSettings.foldersFirst)) },
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("文件夹始终置顶", Modifier.weight(1f))
                    Switch(checked = viewSettings.foldersFirst, onCheckedChange = { updateView(viewSettings.copy(foldersFirst = it)) })
                }
            }
        }, confirmButton = { TextButton(onClick = { viewDialog = false }) { Text("完成") } })
    gitOperation?.let { (metadata, action) ->
        FileGitDialog(api, metadata, action, close = { gitOperation = null }, changed = { refresh++ })
    }
    input?.let { (name, target, initial) ->
        val submit: (String) -> Unit = { dest ->
            val targets = operationTargets.toList()
            input = null
            task {
                when (name) {
                    "新建文件夹" -> api.json("/api/files/create-directory", obj("path" to dest))
                    "新建文件" -> api.json("/api/files/write", obj("path" to dest, "content" to ""))
                    "重命名" ->
                        api.json("/api/files/rename", obj("oldPath" to target, "newPath" to dest))
                    "复制",
                    "移动" ->
                        api.json(
                            "/api/files/${if(name=="复制")"copy" else "move"}-batch",
                            obj(
                                "items" to itemsBody(targets),
                                "destPath" to dest,
                                "overwrite" to false,
                            ),
                        )
                    "压缩" ->
                        api.json(
                            "/api/files/compress",
                            obj(
                                "items" to JSONArray(targets.map { it.optString("path") }),
                                "destZipPath" to dest,
                            ),
                        )
                    "解压" ->
                        api.json(
                            "/api/files/decompress",
                            obj("archivePath" to target, "destPath" to dest),
                        )
                    "修改卷标" ->
                        api.json(
                            "/api/files/set-drive-label",
                            obj("path" to target, "label" to dest),
                        )
                }
                selected = emptySet()
                refresh++
            }
        }
        if (name in listOf("复制", "移动", "解压"))
            PathPicker(api, "$name 到…", initial, directoryOnly = true, close = { input = null }, select = submit)
        else
            InputDialog(
                name,
                if (name == "修改卷标") "卷标名称" else "服务器上的完整目标路径",
                initial,
                { input = null },
                submit,
            )
    }
    pending?.let { (message, action) ->
        ConfirmDialog("文件操作", message, { pending = null }) {
            pending = null
            action()
        }
    }
    document?.let { (target, text, hex) ->
        TextDocument(if (hex) "十六进制 · $target" else target, text, true, { document = null },
            preview = if (!hex && isMarkdownDocument(target)) { { content -> HermesMarkdown(content, Modifier.fillMaxWidth()) } } else null) { value
            ->
            task {
                if (!hex) api.json("/api/files/write", obj("path" to target, "content" to value))
                else {
                    val clean = value.replace(Regex("\\s"), "")
                    require(clean.length % 2 == 0 && clean.matches(Regex("[0-9a-fA-F]*"))) {
                        "请输入有效的十六进制字节"
                    }
                    val data = clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                    val request =
                        Request.Builder()
                            .url(api.base + "/api/files/write-binary?path=${q(target)}")
                            .header("Cookie", api.cookie())
                            .post(data.toRequestBody("application/octet-stream".toMediaType()))
                            .build()
                    api.json(request)
                }
                document = null
                refresh++
                onError("已保存")
            }
        }
    }
    media?.let { file -> MediaViewer(api, file, { media = null }) { download(file) } }
}

internal fun isMarkdownDocument(path: String): Boolean =
    path.substringAfterLast('.').lowercase(java.util.Locale.ROOT) in setOf("md", "markdown", "mdown")

fun isComposeFile(name: String) =
    name.lowercase().matches(Regex("(?:docker-)?compose(?:[.-].+)?\\.ya?ml"))

private fun fileActionIcon(name: String): ImageVector = when (name) {
    "复制路径" -> Icons.Outlined.Link
    "重命名" -> Icons.Outlined.Edit
    "复制" -> Icons.Outlined.ContentCopy
    "移动" -> Icons.Outlined.DriveFileMove
    "压缩" -> Icons.Outlined.FolderZip
    "解压" -> Icons.Outlined.Unarchive
    "下载" -> Icons.Outlined.Download
    "文本编辑" -> Icons.Outlined.EditNote
    "十六进制" -> Icons.Outlined.Code
    "Compose 管理" -> Icons.Outlined.Layers
    "Git 状态" -> Icons.Outlined.AccountTree
    "Git 提交并推送" -> Icons.Outlined.Commit
    "Git 推送" -> Icons.Outlined.CloudUpload
    "Git 拉取" -> Icons.Outlined.CloudDownload
    "修改卷标", "修改磁盘名称" -> Icons.Outlined.DriveFileRenameOutline
    "删除" -> Icons.Outlined.Delete
    else -> Icons.Outlined.MoreHoriz
}

private fun fileIcon(file: JSONObject): ImageVector =
    if (file.optBoolean("isDirectory"))
        if (file.has("totalBytes")) Icons.Outlined.Storage else Icons.Outlined.Folder
    else
        when (file.optString("name").substringAfterLast('.').lowercase()) {
            "jpg",
            "jpeg",
            "png",
            "webp",
            "gif",
            "bmp" -> Icons.Outlined.Image
            "mp4",
            "wmv",
            "mkv",
            "mov",
            "avi",
            "webm" -> Icons.Outlined.VideoFile
            "mp3",
            "flac",
            "wav",
            "aac" -> Icons.Outlined.AudioFile
            "zip",
            "7z",
            "rar",
            "gz",
            "tar" -> Icons.Outlined.FolderZip
            "cs",
            "js",
            "kt",
            "java",
            "py",
            "sh",
            "ps1",
            "html",
            "css",
            "json",
            "xml",
            "yaml",
            "yml" -> Icons.Outlined.Code
            else -> Icons.Outlined.Description
        }

@Composable
private fun FileToolbarButton(label: String, icon: ImageVector, enabled: Boolean, modifier: Modifier, action: () -> Unit) {
    TextButton(onClick = action, enabled = enabled, modifier = modifier.heightIn(min = 52.dp),
        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
        Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}
