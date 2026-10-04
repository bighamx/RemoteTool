@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.InsertDriveFile
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject

data class HermesUpload(val name: String, val body: RequestBody, val temporary: File)

fun prepareHermesUpload(app: Application, uri: Uri, image: Boolean): HermesUpload {
    val resolver = app.contentResolver
    var name = "attachment"
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) name = it.getString(0) ?: name
    }
    var mime = resolver.getType(uri) ?: "application/octet-stream"
    val temporary = File(app.cacheDir, "hermes-upload-${UUID.randomUUID()}")
    try {
        resolver.openInputStream(uri)?.use { input ->
            temporary.outputStream().use { output ->
                val buffer = ByteArray(65536)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 500 * 1024 * 1024) throw java.io.IOException("附件大小限制为 500 MB")
                    output.write(buffer, 0, count)
                }
            }
        } ?: throw java.io.IOException("无法读取所选文件")
        if (image && mime != "image/gif" && Build.VERSION.SDK_INT >= 28) {
            val bitmap =
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(temporary)) { decoder, info, _
                    ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val ratio = minOf(1.0, 2048.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize(
                        (info.size.width * ratio).toInt().coerceAtLeast(1),
                        (info.size.height * ratio).toInt().coerceAtLeast(1),
                    )
                }
            temporary.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            mime = "image/jpeg"
            name = name.substringBeforeLast('.') + ".jpg"
        }
        return HermesUpload(name, temporary.asRequestBody(mime.toMediaType()), temporary)
    } catch (error: Exception) {
        temporary.delete()
        throw error
    }
}

fun downloadHermesFile(context: Context, api: NativeApi, file: JSONObject) {
    val request =
        DownloadManager.Request(Uri.parse(api.base + file.getString("url")))
            .addRequestHeader("Cookie", api.cookie())
            .setTitle(file.getString("name"))
            .setMimeType(file.optString("mime"))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                System.currentTimeMillis().toString() + "_" + file.getString("name"),
            )
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    val downloadId = manager.enqueue(request)
    val prefs = context.getSharedPreferences("hermes-local-files", Context.MODE_PRIVATE)
    val key = file.getString("url").substringBefore('?')
    val ids = prefs.getStringSet(key, emptySet()).orEmpty() + downloadId.toString()
    prefs.edit().putStringSet(key, ids).apply()
}

private fun hasHermesDownload(context: Context, file: JSONObject): Boolean {
    val key = file.getString("url").substringBefore('?')
    val ids = context.getSharedPreferences("hermes-local-files", Context.MODE_PRIVATE)
        .getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }
    if (ids.isEmpty()) return false
    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    return manager.query(DownloadManager.Query().setFilterById(*ids.toLongArray())).use { it.moveToFirst() }
}

private fun clearHermesLocalFile(context: Context, api: NativeApi, file: JSONObject) {
    val prefs = context.getSharedPreferences("hermes-local-files", Context.MODE_PRIVATE)
    val key = file.getString("url").substringBefore('?')
    val ids = prefs.getStringSet(key, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }
    if (ids.isNotEmpty()) (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager)
        .remove(*ids.toLongArray())
    prefs.edit().remove(key).apply()
    // Clear this attachment only, including cached copies fetched through other device channels.
    val endpoints = context.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
        .getString("devices", "[]")?.let { org.json.JSONArray(it).objects() }
        .orEmpty().flatMap { device ->
            val urls = device.array("endpoints")
            (0 until urls.length()).map { urls.getString(it) }
        }
    (endpoints + api.base).distinct().forEach { base ->
        val url = base + file.getString("url")
        context.imageLoader.memoryCache?.remove(coil.memory.MemoryCache.Key(url))
        context.imageLoader.diskCache?.remove(url)
        val prefix = hermesPreviewKey(url)
        File(context.cacheDir, "hermes-open").listFiles()?.filter { it.name.startsWith(prefix + ".") }?.forEach { it.delete() }
    }
}

@Composable
fun HermesFilesDialog(api: NativeApi, files: List<JSONObject>, onClose: () -> Unit) {
    val context = LocalContext.current
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var cleared by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("会话附件 · ${files.size}") },
        text = {
            Column {
            Text("清理手机副本不会删除相册原图或服务端附件。", style = MaterialTheme.typography.bodySmall)
            notice?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            if (clearing) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                Modifier.heightIn(max = 430.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(files, key = { it.getString("id") }) { file ->
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val id = file.getString("id")
                            Checkbox(id in selected, { checked -> selected = if (checked) selected + id else selected - id }, enabled = !clearing)
                            if (file.optString("mime").startsWith("image/"))
                                coil.compose.AsyncImage(
                                    model = coil.request.ImageRequest.Builder(context)
                                        .data(api.base + file.getString("url")).setHeader("Cookie", api.cookie()).build(),
                                    contentDescription = file.getString("name"),
                                    modifier = Modifier.size(56.dp).clickable { preview = file },
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                )
                            else if (hermesFileKind(file) == "视频") HermesVideoThumbnail(api, file, { preview = file }, compact = true)
                            else Icon(Icons.Outlined.InsertDriveFile, null, Modifier.size(40.dp))
                            Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                file.getString("name"),
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                if (id in cleared) "手机副本已清理"
                                else if (hasHermesDownload(context, file)) "手机：已下载"
                                else "手机：未下载 · 预览按需缓存",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Text(
                        (if (file.optBoolean("outgoing")) "Hermes 返回" else "已上传") +
                                    " · " +
                                    bytes(file.optDouble("size")),
                                style = MaterialTheme.typography.labelSmall,
                            )
                            Row {
                                TextButton(onClick = { preview = file }) { Text("打开") }
                                TextButton(onClick = { downloadHermesFile(context, api, file) }) {
                                    Text("下载")
                                }
                            }
                        }
                        }
                    }
                }
            }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("关闭") } },
        dismissButton = {
            TextButton(onClick = { confirm = true }, enabled = selected.isNotEmpty() && !clearing) {
                Text("清理手机副本 (${selected.size})")
            }
        },
    )
    if (confirm) ConfirmDialog("清理手机副本", "删除选中附件的 App 预览缓存和 App 下载的文件。相册原图、服务端附件及会话历史保留。", { confirm = false }) {
        confirm = false
        clearing = true
        val targets = files.filter { it.getString("id") in selected }
        scope.launch {
            try {
                withContext(Dispatchers.IO) { targets.forEach { clearHermesLocalFile(context, api, it) } }
                cleared = cleared + targets.map { it.getString("id") }
                selected = emptySet()
                notice = "已清理 ${targets.size} 个附件的手机副本；再次预览会重新缓存。"
            } catch (error: Exception) {
                notice = "清理失败：${error.message}"
            } finally { clearing = false }
        }
    }
    preview?.let { file ->
        HermesAttachmentViewer(api, file) { preview = null }
    }
}
