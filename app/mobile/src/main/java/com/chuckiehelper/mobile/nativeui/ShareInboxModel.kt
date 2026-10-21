package com.chuckiehelper.mobile.nativeui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import okhttp3.MultipartBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

internal fun sharedFileName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\')
    .replace(Regex("[\\p{Cntrl}:*?\"<>|]"), "_").take(180).trim().takeUnless { it.isBlank() || it == "." || it == ".." } ?: "attachment"

@Suppress("DEPRECATION")
internal fun sharedUris(intent: Intent): List<Uri> {
    if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return emptyList()
    val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE)
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
    else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
    val clip = intent.clipData?.let { data -> (0 until data.itemCount).mapNotNull { data.getItemAt(it).uri } }.orEmpty()
    return (streams + clip).distinct().filter { it.scheme == "content" }
}

data class SharedChatDraft(val id: String, val deviceId: String, val agent: String, val session: JSONObject,
    val text: String, val attachments: List<JSONObject>)
data class SharedDirectoryUpload(val deviceId: String, val path: String, val count: Int)

/** Copy granted content immediately; no dependency on the sender after returning to it. */
class ShareInboxModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    var pending by mutableStateOf(saved.get<Boolean>("pending") ?: false); private set
    var importing by mutableStateOf(false); private set
    var uploading by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var message by mutableStateOf(saved.get<String>("text") ?: "")
    var text: String
        get() = message
        set(value) { message = value; saved["text"] = value }
    var files by mutableStateOf(saved.get<ArrayList<String>>("files")?.map(::File).orEmpty()); private set
    private var mimes = saved.get<ArrayList<String>>("mimes").orEmpty()
    var readable by mutableStateOf(saved.get<Boolean>("readable") ?: false); private set
    var ready by mutableStateOf<SharedChatDraft?>(null); private set
    var directoryResult by mutableStateOf<SharedDirectoryUpload?>(null); private set
    var directoryConflicts by mutableStateOf<List<String>>(emptyList()); private set
    var uploadedCount by mutableIntStateOf(0); private set
    private var importJob: Job? = null
    private val uploaded = mutableMapOf<String, JSONObject>()
    private var destination = ""

    fun receive(intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) return
        if (pending || uploading || ready != null) {
            error = "请先处理当前分享，再次分享其他文件"; return
        }
        pending = true; saved["pending"] = true; error = null; readable = false
        text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().take(100_000)
        saved["text"] = text
        val uris = sharedUris(intent)
        if (uris.size > 8) { error = "每条消息最多分享 8 个文件，请重新选择"; return }
        if (uris.isEmpty() && text.isBlank()) { error = "没有可读取的分享文件或文字"; return }
        importing = true
        importJob = viewModelScope.launch {
            val copied = mutableListOf<File>()
            val copiedMimes = arrayListOf<String>()
            try {
                withContext(Dispatchers.IO) {
                    for (uri in uris) {
                        ensureActive()
                        val resolver = getApplication<Application>().contentResolver
                        copiedMimes += resolver.getType(uri) ?: intent.type ?: "application/octet-stream"
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        } ?: "attachment"
                        val folder = File(getApplication<Application>().cacheDir, "share-inbox/${UUID.randomUUID()}")
                        folder.mkdirs()
                        val file = File(folder, sharedFileName(name)); copied += file
                        resolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                            val buffer = ByteArray(65536); var total = 0L
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer); if (count < 0) break
                                total += count
                                if (total > 500L * 1024 * 1024) throw IOException("附件大小限制为 500 MB")
                                output.write(buffer, 0, count)
                            }
                        } } ?: throw IOException("无法读取分享文件：${file.name}")
                    }
                }
                files = copied.toList(); saved["files"] = ArrayList(files.map { it.absolutePath })
                mimes = copiedMimes; saved["mimes"] = copiedMimes
                readable = true; saved["readable"] = true
            } catch (e: CancellationException) { copied.forEach { it.delete() }; throw e }
            catch (e: Exception) { copied.forEach { it.delete() }; error = e.message ?: "读取分享文件失败" }
            finally { importing = false }
        }
    }

    fun dismiss() {
        if (uploading) return
        importJob?.cancel(); files.forEach { it.delete(); it.parentFile?.delete() }
        files = emptyList(); uploaded.clear(); destination = ""; pending = false; ready = null; error = null
        directoryResult = null; directoryConflicts = emptyList(); uploadedCount = 0
        readable = false; saved["pending"] = false; saved["readable"] = false
        saved.remove<ArrayList<String>>("files"); saved.remove<String>("text")
        saved.remove<ArrayList<String>>("mimes"); mimes = emptyList()
    }

    fun prepare(api: NativeApi, device: String, agent: String, session: JSONObject) {
        if (uploading || importing || !readable) return
        val target = "$device:$agent:${session.getString("id")}"
        uploadFiles(api, target, "/api/$agent/sessions/${android.net.Uri.encode(session.getString("id"))}/files") {
            ready = SharedChatDraft(UUID.randomUUID().toString(), device, agent, session, text,
                files.mapNotNull { uploaded[it.absolutePath] })
        }
    }

    fun clearDirectoryConflicts() { directoryConflicts = emptyList() }
    fun conflictsForDirectory(device: String, path: String): List<String> =
        if (destination == "files:$device:${directoryPositionKey(path)}") directoryConflicts else emptyList()

    fun uploadToDirectory(api: NativeApi, device: String, path: String, overwrite: Boolean = false) {
        if (uploading || importing || !readable || files.isEmpty() || path.isBlank()) return
        val target = "files:$device:${directoryPositionKey(path)}"
        directoryResult = null; directoryConflicts = emptyList()
        uploadFiles(api, target, "/api/files/upload?path=${android.net.Uri.encode(path)}", check = {
            val entries = api.json("/api/files/list?path=${android.net.Uri.encode(path)}").array("data").objects()
            val remaining = files.filterNot { uploaded.containsKey(it.absolutePath) }
            val collisions = remaining.filter { file -> entries.any { entry ->
                entry.optString("name").equals(file.name, ignoreCase = !path.startsWith('/'))
            } }.map { it.name }.distinct()
            if (!overwrite && collisions.isNotEmpty()) { directoryConflicts = collisions; false } else true
        }) { directoryResult = SharedDirectoryUpload(device, path, files.size) }
    }

    private fun uploadFiles(api: NativeApi, target: String, route: String,
        check: suspend () -> Boolean = { true }, done: () -> Unit) {
        if (destination != target) { uploaded.clear(); uploadedCount = 0; destination = target }
        uploading = true; error = null
        viewModelScope.launch {
            try {
                if (!check()) return@launch
                for ((index, file) in files.withIndex()) {
                    if (uploaded.containsKey(file.absolutePath)) continue
                    if (!file.isFile) throw IOException("分享文件已失效，请重新分享：${file.name}")
                    val mime = mimes.getOrNull(index)?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("file", file.name, file.asRequestBody(mime)).build()
                    val request = api.request(route)
                        .newBuilder().post(body).build()
                    uploaded[file.absolutePath] = api.json(request)
                    uploadedCount = uploaded.size
                }
                done()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "分享附件上传失败，请重试" }
            finally { uploading = false }
        }
    }
    fun finishDelivery(accepted: Boolean) {
        if (accepted) dismiss()
        else { ready = null; error = "会话附件仍在上传或合并后超过 8 个，请取消分享后处理会话中的待发附件，再重试" }
    }
}
