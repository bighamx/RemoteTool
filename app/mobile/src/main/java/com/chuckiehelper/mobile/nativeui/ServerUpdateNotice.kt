package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
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
    var connectionError by remember(deviceId, api) { mutableStateOf<String?>(null) }
    var busy by remember(deviceId, api) { mutableStateOf(false) }
    var tracking by remember(deviceId, api) { mutableStateOf(false) }
    var force by remember(deviceId, api) { mutableStateOf(false) }
    var submitting by remember(deviceId, api) { mutableStateOf<String?>(null) }
    var revision by remember(deviceId, api) { mutableIntStateOf(0) }
    var uncertain by remember(deviceId, api) { mutableStateOf(false) }
    var currentJobId by remember(deviceId, api) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(deviceId, api, lifecycle, tracking, shown, requested) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
        while (true) {
            // A GET started before the POST must not replace its accepted job with an old result.
            if (busy) { delay(250); continue }
            val started = revision
            try {
                val value = api.json("/api/server-update")
                if (busy || started != revision) { delay(250); continue }
                uncertain = false
                state = value
                if ((shown || requested) && updateJobActive(value.optJSONObject("job"))) {
                    currentJobId = value.optJSONObject("job")?.updateText("id")?.takeIf { it.isNotBlank() }
                }
                onSupported(value.optBoolean("supported"))
                val check = value.optJSONObject("check")
                if (value.updateText("mode") != "source" && check?.optBoolean("available") == true && check.optLong("releaseSequence") != dismissed) shown = true
                tracking = updateJobActive(value.optJSONObject("job"))
                connectionError = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { if (tracking) connectionError = "服务端更新中，正在重连并核对结果…" }
            delay(if (tracking || shown || requested) 2000 else 60000)
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
    val sourceAction = sourceUpdateAction(snapshot)
    val actionable = if (source) sourceAction != null else check?.optBoolean("available") == true
    val updating = uncertain || updateJobActive(job) || job?.updateText("phase") == "recovery_required"
    var checking by remember(deviceId,api) { mutableStateOf(false) }
    fun close() { revision++; dismissed = check?.optLong("releaseSequence") ?: -1; shown = false; force = false; currentJobId = null; onClosed() }
    fun run(path: String, body: JSONObject) {
        if (busy) return
        busy = true
        revision++
        val requestRevision = revision
        val updatingRequest = path == "rebuild" || path == "install"
        if (updatingRequest) {
            submitting = if (body.optBoolean("pullSource")) "正在提交拉取并更新请求…" else if (path == "rebuild") "正在提交编译并重启请求…" else "正在提交更新请求…"
            tracking = true
        }
        checking = path == "check"
        scope.launch {
            try {
                val value = api.json("/api/server-update/$path", body)
                if (updatingRequest) {
                    if (requestRevision == revision) currentJobId = value.optJSONObject("job")?.updateText("id")?.takeIf { it.isNotBlank() }
                    force = false
                }
                state = value; error = null
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                if (updatingRequest) uncertain = true
                error = updateErrorText(failure.message.orEmpty()) + if (updatingRequest) "；正在核对任务是否已受理，请勿重复提交" else ""
            }
            finally { busy = false; checking = false; submitting = null }
        }
    }
    AlertDialog(onDismissRequest = { close() },
        title = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("服务端更新", modifier = Modifier.weight(1f, fill = false))
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                    Text(if (source) "源码部署" else "发行包部署", modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val sourceCheck = check?.takeIf { it.updateText("kind") == "source" }
                        UpdateVersionColumn("运行版本", snapshot.updateText("currentVersion"), Modifier.weight(1f))
                        if (source) {
                            UpdateVersionColumn("本地源码", sourceCheck?.updateText("localRevision").orEmpty(), Modifier.weight(1f))
                            UpdateVersionColumn("远端源码", sourceCheck?.updateText("latestRevision").orEmpty(), Modifier.weight(1f))
                        }
                    }
                }
                val checkText = updateCheckText(snapshot,checking)
                Text(checkText, color = if (checkText == "已是最新版本") MaterialTheme.colorScheme.primary else LocalContentColor.current)
                if (!source) {
                    check?.updateText("releaseNotes")?.takeIf { it.isNotBlank() }?.let { Text(it) }
                }
                val progressTitle = submitting ?: if (uncertain) "正在核对更新任务是否已受理…" else updateJobTitle(job, currentJobId)
                if (progressTitle.isNotBlank()) {
                    Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(progressTitle, style = MaterialTheme.typography.titleSmall)
                            if (submitting != null || uncertain || updateJobActive(job)) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            if (submitting == null && !uncertain) job?.updateText("message")?.takeIf { it.isNotBlank() }?.let { Text(updateErrorText(it)) }
                            if (uncertain || updateJobActive(job)) Text("进度自动刷新；服务重启期间会暂时断开并自动重连。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (!installed) Text(snapshot.updateText("message"))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                connectionError?.let { Text(it) }
                if (supportsForce && actionable) {
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
            if (actionable) Button(enabled = installed && !busy && !updating, onClick = {
                val body = JSONObject().put("force", supportsForce && force)
                if (source) run("rebuild", body.put("pullSource", sourceAction == SourceUpdateAction.PULL)) else run("install", body.put("sequence", check?.optLong("releaseSequence")))
            }) { Text(if (source) sourceAction!!.label else "更新并重启") }
        },
        dismissButton = { Row {
            TextButton(enabled = !busy, onClick = { run("check", JSONObject()) }) { Text("立即检查") }
            TextButton(onClick = { close() }) { Text("关闭") }
        } }
    )
}

@Composable
private fun UpdateVersionColumn(label: String, version: String, modifier: Modifier = Modifier) {
    // Keep hashes compact enough for three columns, including the existing dirty marker.
    val display = if (version.matches(Regex("[a-fA-F0-9]{12,40}(-dirty)?"))) {
        version.take(8) + if (version.endsWith("-dirty")) "\n-dirty" else ""
    } else version.ifBlank { "未获取" }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(display, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
