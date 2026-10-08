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
fun FilesScreen(api: NativeApi, onError: (String) -> Unit, onCompose: (String) -> Unit) {
    var path by rememberSaveable { mutableStateOf("") }
    var inputPath by rememberSaveable { mutableStateOf("") }
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
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val visibleFiles = files.orEmpty()
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
            path.isNotEmpty() &&
                document == null &&
                media == null &&
                input == null &&
                pending == null
    ) {
        navigate(parentPath(path))
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
                            "修改磁盘名称" -> file.optString("name")
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
        Row(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = { navigate(parentPath(path)) }, enabled = path.isNotEmpty()) {
                Icon(Icons.Outlined.ArrowUpward, "上一级")
            }
            OutlinedTextField(
                inputPath,
                { inputPath = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("路径") },
                trailingIcon = {
                    IconButton(onClick = { navigate(inputPath) }) {
                        Icon(Icons.Outlined.ArrowForward, "前往")
                    }
                },
            )
            IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        FlowRowCompat {
            TextButton(
                enabled = !busy && path.isNotBlank(),
                onClick = { input = Triple("新建文件夹", "", path) },
            ) {
                Icon(Icons.Outlined.CreateNewFolder, null)
                Text("新建")
            }
            TextButton(
                enabled = !busy && path.isNotBlank(),
                onClick = { uploader.launch(arrayOf("*/*")) },
            ) {
                Icon(Icons.Outlined.UploadFile, null)
                Text("上传")
            }
            if (selected.isNotEmpty()) {
                Text("已选 ${selected.size}", Modifier.padding(12.dp))
                listOf("复制", "移动", "删除").forEach { name ->
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            operation(
                                name,
                                files.orEmpty().filter { it.optString("path") in selected },
                            )
                        },
                    ) {
                        Text(name)
                    }
                }
                TextButton(onClick = { selected = emptySet() }) { Text("取消") }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (files == null || filesPath != path) ErrorPane(error) { refresh++ }
        else
            LazyColumn(
                state = fileScroll,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                if (visibleFiles.isEmpty()) item { Text("此目录为空", Modifier.padding(24.dp)) }
                items(visibleFiles, key = { it.optString("path") }) { file ->
                    val target = file.optString("path")
                    val isDir = file.optBoolean("isDirectory")
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (selected.isNotEmpty())
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
                                    onLongClick = { selected = selected + target },
                                )
                                .padding(start = 14.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            if (selected.isNotEmpty())
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
                                    Modifier.size(30.dp),
                                    tint =
                                        if (isDir) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondary,
                                )
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(
                                    file.optString("name"),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                val modified = if (file.has("totalBytes")) "" else formatFileModifiedTime(file.optString("modified"))
                                Text(
                                    (if (isDir)
                                        if (file.has("totalBytes"))
                                            "可用 ${bytes(file.optDouble("freeBytes"))} / ${bytes(file.optDouble("totalBytes"))}"
                                        else "文件夹"
                                    else bytes(file.optDouble("size"))) +
                                        if (modified.isNotEmpty()) " · $modified" else "",
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Box {
                                IconButton(onClick = { menuFile = file }) {
                                    Icon(Icons.Outlined.MoreVert, "操作")
                                }
                                DropdownMenu(
                                    expanded = menuFile?.optString("path") == target,
                                    onDismissRequest = { menuFile = null },
                                ) {
                                    val actions = mutableListOf("复制路径", "重命名", "复制", "移动", "压缩")
                                    if (
                                        !isDir &&
                                            file
                                                .optString("name")
                                                .substringAfterLast('.')
                                                .lowercase() in listOf("yml", "yaml")
                                    )
                                        actions.add(0, "Compose 管理")
                                    if (!isDir) actions.addAll(listOf("下载", "文本编辑", "十六进制"))
                                    if (
                                        file
                                            .optString("name")
                                            .substringAfterLast('.')
                                            .lowercase() in listOf("zip", "rar", "7z", "gz", "tar")
                                    )
                                        actions.add("解压")
                                    if (file.has("totalBytes")) actions.add("修改磁盘名称")
                                    actions.add("删除")
                                    actions.forEach { name ->
                                        DropdownMenuItem(
                                            text = { Text(name) },
                                            onClick = { operation(name, listOf(file)) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
    }
    input?.let { (name, target, initial) ->
        val submit: (String) -> Unit = { dest ->
            val targets = operationTargets.toList()
            input = null
            task {
                when (name) {
                    "新建文件夹" -> api.json("/api/files/create-directory", obj("path" to dest))
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
                    "修改磁盘名称" ->
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
                if (name == "修改磁盘名称") "磁盘名称" else "服务器上的完整目标路径",
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
        TextDocument(if (hex) "十六进制 · $target" else target, text, true, { document = null }) { value
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

fun isComposeFile(name: String) =
    name.lowercase().matches(Regex("(?:docker-)?compose(?:[.-].+)?\\.ya?ml"))

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
