package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Per connected device; an older server's missing endpoint is silently supported. */
@Composable
fun ServerUpdateNotice(api: NativeApi, deviceId: String, requested: Boolean = false,
    onClosed: () -> Unit = {}, onSupported: (Boolean) -> Unit = {}) {
    var state by remember(deviceId, api) { mutableStateOf<JSONObject?>(null) }
    var shown by remember(deviceId, api) { mutableStateOf(false) }
    var dismissed by remember(deviceId, api) { mutableLongStateOf(-1) }
    var error by remember(deviceId, api) { mutableStateOf<String?>(null) }
    var busy by remember(deviceId, api) { mutableStateOf(false) }
    var tracking by remember(deviceId, api) { mutableStateOf(false) }
    var force by remember(deviceId, api) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(deviceId, api, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            try {
                val value = api.json("/api/server-update")
                state = value
                onSupported(value.optBoolean("supported"))
                val check = value.optJSONObject("check")
                if (value.updateText("mode") != "source" && check?.optBoolean("available") == true && check.optLong("releaseSequence") != dismissed) shown = true
                if (value.optJSONObject("job")?.optString("phase") in listOf("completed", "rolled_back", "failed", "recovery_required")) tracking = false
                error = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (tracking) error = "服务端更新中，正在重连并核对结果…" }
            delay(if (tracking) 3000 else 60000)
        }
        }
    }
    if (!shown && !requested) return
    val snapshot = state ?: return
    val check = snapshot.optJSONObject("check")
    val job = snapshot.optJSONObject("job")
    val installed = snapshot.optBoolean("installed")
    val source = snapshot.updateText("mode") == "source"
    val supportsForce = snapshot.optBoolean("supportsForce")
    val updating = job?.updateText("phase") in listOf("requested", "waiting", "building", "downloading", "switching", "verifying", "rolling_back", "migrating", "recovery_required")
    fun close() { dismissed = check?.optLong("releaseSequence") ?: -1; shown = false; force = false; onClosed() }
    fun run(path: String, body: JSONObject) {
        if (busy) return
        busy = true
        scope.launch {
            try { state = api.json("/api/server-update/$path", body); error = null; if (path == "rebuild" || path == "install") force = false }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = updateErrorText(failure.message.orEmpty()) }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { close() },
        title = { Text("服务端更新") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前版本 " + snapshot.updateText("currentVersion"))
                Text(if (source) "源码运行 · 使用当前磁盘源码编译，不自动拉取代码" else "正式部署 · 下载发行包更新")
                if (!source) {
                    if (check?.optBoolean("available") == true) Text("可更新至 " + check.updateText("version"))
                    check?.updateText("error")?.takeIf { it.isNotBlank() }?.let { Text(updateErrorText(it)) }
                    check?.updateText("releaseNotes")?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
                job?.updateText("message")?.takeIf { it.isNotBlank() }?.let { Text(updateErrorText(it)) }
                if (!installed) Text(snapshot.updateText("message"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (supportsForce) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = force, enabled = !busy && !updating, onCheckedChange = { force = it })
                        Text("强制中断任务")
                    }
                    if (force) Text("仅本次生效：断开连接并中断 RemoteTool 的 Codex 任务；Hermes 独立任务继续运行。", style = MaterialTheme.typography.bodySmall)
                }
                if (installed && !source) {
                    val policy = snapshot.optJSONObject("policy")
                    Text("更新方式")
                    if (policy?.updateText("mode") == "automatic") Text(updateWindowText(policy), style = MaterialTheme.typography.bodySmall)
                    listOf("notify" to "仅提醒", "download" to "自动下载，手动安装", "automatic" to "空闲时自动更新并重启").forEach { (mode, label) ->
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val policy = snapshot.optJSONObject("policy") ?: JSONObject()
                                    val request = api.request("/api/server-update/settings", null).newBuilder().put(
                                        JSONObject(policy.toString()).put("mode", mode).toString().toRequestBody("application/json".toMediaType())).build()
                                    state = api.json(request)
                                } catch (cancel: CancellationException) { throw cancel }
                                catch (failure: Exception) { error = updateErrorText(failure.message.orEmpty()) }
                                finally { busy = false }
                            }
                        }) { Text((if (snapshot.optJSONObject("policy")?.optString("mode") == mode) "✓ " else "") + label) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = installed && (if (source) snapshot.optBoolean("canRebuild") else check?.optBoolean("available") == true) && !busy && !updating, onClick = {
                tracking = true
                val body = JSONObject().put("force", supportsForce && force)
                if (source) run("rebuild", body) else run("install", body.put("sequence", check?.optLong("releaseSequence")))
            }) { Text(if (source) "编译并重启" else "更新并重启") }
        },
        dismissButton = { Row {
            TextButton(enabled = !busy, onClick = { run("check", JSONObject()) }) { Text("立即检查") }
            TextButton(onClick = { close() }) { Text("稍后") }
        } }
    )
}
