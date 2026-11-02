package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
                if (check?.optBoolean("available") == true && check.optLong("releaseSequence") != dismissed) shown = true
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
    fun run(path: String, body: JSONObject) {
        if (busy) return
        busy = true
        scope.launch {
            try { state = api.json("/api/server-update/$path", body); error = null }
            catch (failure: Exception) { error = failure.message }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = { dismissed = check?.optLong("releaseSequence") ?: -1; shown = false; onClosed() },
        title = { Text("服务端更新") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (check?.optBoolean("available") == true) "发现版本 " + check.optString("version") else "当前版本 " + snapshot.optString("currentVersion"))
                check?.optString("error")?.takeIf { it.isNotBlank() }?.let { Text(it) }
                Text(check?.optString("releaseNotes").orEmpty())
                job?.optString("message")?.takeIf { it.isNotBlank() }?.let { Text(it) }
                if (!installed) Text(snapshot.optString("message"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (snapshot.optBoolean("canRebuild")) TextButton(enabled = !busy && job?.optString("phase") !in listOf("requested", "waiting", "building", "switching", "verifying", "rolling_back", "recovery_required"), onClick = {
                    tracking = true; run("rebuild", JSONObject())
                }) { Text("停止 Web/bridge 并重编译当前源码") }
                if (installed) {
                    val policy = snapshot.optJSONObject("policy")
                    Text("更新方式（自动安装时段 UTC ${policy?.optInt("startHourUtc",18)}–${policy?.optInt("endHourUtc",22)} 时）")
                    listOf("notify" to "仅提醒", "download" to "自动下载，手动安装", "automatic" to "空闲时自动更新并重启").forEach { (mode, label) ->
                        TextButton(enabled = !busy, onClick = {
                            scope.launch {
                                busy = true
                                try {
                                    val policy = snapshot.optJSONObject("policy") ?: JSONObject()
                                    val request = api.request("/api/server-update/settings", null).newBuilder().put(
                                        JSONObject(policy.toString()).put("mode", mode).toString().toRequestBody("application/json".toMediaType())).build()
                                    state = api.json(request)
                                } catch (failure: Exception) { error = failure.message }
                                finally { busy = false }
                            }
                        }) { Text((if (snapshot.optJSONObject("policy")?.optString("mode") == mode) "✓ " else "") + label) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = installed && check?.optBoolean("available") == true && !busy && job?.optString("phase") !in listOf("requested", "waiting", "building", "downloading", "switching", "verifying", "rolling_back", "migrating", "recovery_required"), onClick = {
                tracking = true; run("install", JSONObject().put("sequence", check?.optLong("releaseSequence")))
            }) { Text("更新并重启") }
        },
        dismissButton = { Row {
            TextButton(enabled = !busy, onClick = { run("check", JSONObject()) }) { Text("立即检查") }
            TextButton(onClick = { dismissed = check?.optLong("releaseSequence") ?: -1; shown = false; onClosed() }) { Text("稍后") }
        } }
    )
}
