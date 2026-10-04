@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.chuckiehelper.mobile.nativeui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

fun hermesPreviewKey(url: String): String = MessageDigest.getInstance("SHA-256")
    .digest(url.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

@Composable
fun HermesAttachmentCard(file: JSONObject, open: () -> Unit) {
    val kind = hermesFileKind(file)
    val icon = when (kind) {
        "视频" -> Icons.Outlined.PlayCircle
        "音频" -> Icons.Outlined.Audiotrack
        "文本" -> Icons.Outlined.Description
        else -> Icons.Outlined.InsertDriveFile
    }
    Surface(
        modifier = Modifier.padding(top = 8.dp).widthIn(max = 300.dp).fillMaxWidth().clickable(onClick = open),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(file.optString("name"), maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall)
                Text("$kind · ${bytes(file.optDouble("size"))} · 点击打开", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun HermesVideoThumbnail(api: NativeApi, file: JSONObject, open: () -> Unit, compact: Boolean = false) {
    val context = LocalContext.current
    val url = api.base + file.getString("url")
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            val directory = File(context.cacheDir, "hermes-open").apply { mkdirs() }
            val cached = File(directory, hermesPreviewKey(url) + ".thumb.jpg")
            android.graphics.BitmapFactory.decodeFile(cached.path)?.let { return@withContext it }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(url, mapOf("Cookie" to api.cookie()))
                val frame = if (Build.VERSION.SDK_INT >= 27)
                    retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 480, 270)
                else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let {
                    Bitmap.createScaledBitmap(it, 480, 270, true).also { scaled -> if (scaled !== it) it.recycle() }
                }
                frame?.let { image -> cached.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 85, it) } }
                frame
            } catch (_: Exception) { null }
            finally { retriever.release() }
        }
        loading = false
    }
    Surface(
        modifier = if (compact) Modifier.size(56.dp).clickable(onClick = open)
            else Modifier.padding(top = 8.dp).widthIn(max = 240.dp).fillMaxWidth().clickable(onClick = open),
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Column {
            Box(if (compact) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it.asImageBitmap(), file.optString("name"), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (loading) CircularProgressIndicator(Modifier.size(24.dp))
                else Surface(shape = androidx.compose.foundation.shape.CircleShape, color = androidx.compose.ui.graphics.Color.Black.copy(alpha = .55f)) {
                    Icon(Icons.Outlined.PlayArrow, "播放视频", Modifier.padding(8.dp).size(if (compact) 20.dp else 30.dp), tint = androidx.compose.ui.graphics.Color.White)
                }
            }
            if (!compact) Column(Modifier.padding(10.dp)) {
                Text(file.optString("name"), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                Text("视频 · ${bytes(file.optDouble("size"))}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private suspend fun cacheHermesDocument(context: Context, api: NativeApi, file: JSONObject): File = withContext(Dispatchers.IO) {
    val url = api.base + file.getString("url")
    val ext = file.optString("name").substringAfterLast('.', "bin").filter { it.isLetterOrDigit() }.take(12)
    val directory = File(context.cacheDir, "hermes-open").apply { mkdirs() }
    val destination = File(directory, hermesPreviewKey(url) + ".$ext")
    if (destination.exists() && destination.length() == file.optLong("size")) return@withContext destination
    val temporary = File(directory, destination.name + ".part")
    try {
        api.response(api.request(file.getString("url"))).use { response ->
            if (!response.isSuccessful) throw java.io.IOException("读取附件失败 HTTP ${response.code}")
            response.body!!.byteStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
        }
        if (!temporary.renameTo(destination)) throw java.io.IOException("无法保存预览缓存")
        destination
    } finally { temporary.delete() }
}

@Composable
fun HermesAttachmentViewer(api: NativeApi, file: JSONObject, close: () -> Unit) {
    val context = LocalContext.current
    val kind = hermesFileKind(file)
    if (kind in listOf("图片", "视频", "音频")) {
        MediaViewer(api, file, close) { downloadHermesFile(context, api, file) }
        return
    }
    var text by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(api.base, file.getString("url")) {
        try {
            if (kind == "文本" && file.optLong("size") <= 1024 * 1024) {
                text = withContext(Dispatchers.IO) {
                    api.response(api.request(file.getString("url"))).use {
                        if (!it.isSuccessful) throw java.io.IOException("读取失败 HTTP ${it.code}")
                        it.body!!.string()
                    }
                }
            } else {
                val local = cacheHermesDocument(context, api, file)
                val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".hermes.files", local)
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(local.extension.lowercase())
                    ?: file.optString("mime", "application/octet-stream")
                val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try { context.startActivity(intent); close() }
                catch (_: android.content.ActivityNotFoundException) { error = "手机尚未安装可打开此格式的应用" }
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "打开失败" }
        finally { loading = false }
    }
    AlertDialog(
        onDismissRequest = close,
        title = { Text(file.optString("name"), maxLines = 2) },
        text = {
            Column {
                if (loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在打开…") }
                error?.let { Text(it) }
                text?.let { value ->
                    androidx.compose.foundation.text.selection.SelectionContainer {
                        Column(Modifier.heightIn(max = 430.dp).then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))) {
                            HermesMarkdown(value)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("关闭") } },
        dismissButton = { TextButton(onClick = { downloadHermesFile(context, api, file) }) { Text("下载") } },
    )
}
