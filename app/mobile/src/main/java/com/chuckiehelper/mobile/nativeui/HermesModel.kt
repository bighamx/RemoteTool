package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

data class HermesMessage(
    val role: String,
    val text: String,
    val serverId: Long = 0,
    val attachments: List<JSONObject> = emptyList(),
)

data class HermesEvent(val type: String, val text: String, val detail: String = "")

/** Durable run ids, never a persisted provider secret or automatically replayed prompt. */
class HermesModel(application: Application, deviceId: String) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("hermes-$deviceId", 0)
    private lateinit var api: NativeApi
    var sessions by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var selectedId by mutableStateOf(prefs.getString("session", null))
        private set

    var title by mutableStateOf("Hermes")
        private set

    var messages by mutableStateOf<List<HermesMessage>>(emptyList())
        private set

    var events by mutableStateOf<List<HermesEvent>>(emptyList())
        private set

    var draft by mutableStateOf("")
    var pendingText by mutableStateOf("")
        private set

    var runId by mutableStateOf(prefs.getString("run", null))
        private set

    var runSession by mutableStateOf(prefs.getString("runSession", null))
        private set

    var state by mutableStateOf("就绪")
        private set

    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
        private set

    var submitting by mutableStateOf(false)
        private set

    var uploading by mutableStateOf(false)
        private set

    var files by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var pendingFiles by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var capabilities by mutableStateOf(JSONObject())
        private set

    var modelOptions by mutableStateOf(JSONObject())
        private set

    var providers by mutableStateOf(JSONObject())
        private set

    var approval by mutableStateOf<JSONObject?>(null)
        private set

    var runtime by mutableStateOf("")
        private set

    var modelWarning by mutableStateOf<String?>(null)

    var hasMore by mutableStateOf(false)
        private set

    private var watching: Job? = null
    private var historyJob: Job? = null
    private var streamJob: Job? = null
    private var seq = -1L
    private var optimisticKey: String? = null
    private var optimisticText: String? = null
    private var optimisticSession: String? = null
    private var optimisticAfter = 0L
    private var optimisticFiles: List<JSONObject> = emptyList()

    fun bind(value: NativeApi) {
        if (this::api.isInitialized && api.base == value.base) return
        api = NativeApi(value.base, noRetry = true)
        watching?.cancel()
        streamJob?.cancel()
        launch {
            capabilities = api.json("/api/hermes/capabilities")
            if (capabilities.optJSONObject("features")?.optBoolean("run_submission") != true)
                throw java.io.IOException("当前 Hermes 不支持可恢复任务接口")
            refreshSessions()
            fetchModels()
            selectedId?.let { loadHistory(it) }
            if (runId != null) watch()
            else if (hasPendingSubmission) error = "上次任务提交结果尚未确认，请使用原标识核对并重试"
        }
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit) =
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Hermes 请求失败"
            }
        }

    fun refresh() = launch {
        refreshSessions()
        selectedId?.let { loadHistory(it) }
    }

    private suspend fun refreshSessions(offset: Int = 0) {
        loading = true
        try {
            val result = api.json("/api/hermes/sessions?offset=$offset")
            sessions =
                if (offset == 0) result.array("data").objects()
                else (sessions + result.array("data").objects()).distinctBy { it.optString("id") }
            hasMore = result.optBoolean("has_more")
        } finally {
            loading = false
        }
    }

    fun moreSessions() = launch { refreshSessions(sessions.size) }

    fun select(session: JSONObject) {
        selectedId = session.getString("id")
        title = session.optString("title").ifBlank { "未命名会话" }
        prefs.edit().putString("session", selectedId).apply()
        historyJob?.cancel()
        messages = emptyList()
        pendingFiles = emptyList()
        historyJob = launch { loadHistory(selectedId!!) }
    }

    private suspend fun loadHistory(id: String) {
        val response = api.json("/api/hermes/sessions/$id/messages")
        if (id != selectedId) return
        messages =
            response.array("data").objects().mapNotNull { row ->
                val role = row.optString("role")
                val text =
                    row.optString("content").let {
                        if (role == "user") it.substringBefore("\n\n[ChuckieHelper 持久附件]") else it
                    }
                if (role in listOf("user", "assistant") && text.isNotBlank() && text != "null")
                    HermesMessage(role, text, row.optLong("id"), row.array("attachments").objects())
                else null
            }
        if (optimisticSession == id && optimisticText != null) {
            val acknowledged =
                messages.firstOrNull {
                    it.role == "user" &&
                        it.serverId > optimisticAfter &&
                        it.text.contains(optimisticText!!)
                }
            if (acknowledged != null) {
                if (optimisticFiles.isNotEmpty()) {
                    api.json(
                        "/api/hermes/sessions/$id/messages/${acknowledged.serverId}/attachments",
                        obj("ids" to org.json.JSONArray(optimisticFiles.map { it.getString("id") })),
                    )
                    messages =
                        messages.map {
                            if (it.serverId == acknowledged.serverId)
                                it.copy(attachments = optimisticFiles)
                            else it
                        }
                }
                optimisticText = null
            } else
                messages =
                    messages +
                        HermesMessage("user", optimisticText!!, attachments = optimisticFiles)
        }
        sessions
            .find { it.optString("id") == id }
            ?.let { title = it.optString("title").ifBlank { "未命名会话" } }
        if (selectedId == id)
            files = api.json("/api/hermes/sessions/$id/files").array("data").objects()
    }

    fun newSession(name: String, onDone: () -> Unit = {}) = launch {
        if (submitting) return@launch
        submitting = true
        try {
            val result =
                api.json(
                    "/api/hermes/sessions",
                    obj("title" to name.ifBlank { newHermesChatName() }),
                )
            val session = result.getJSONObject("session")
            select(session)
            onDone()
            refreshSessions()
        } finally {
            submitting = false
        }
    }

    fun renameSession(id: String, name: String, onDone: () -> Unit) = launch {
        val body = obj("title" to name.trim()).toString().toRequestBody("application/json".toMediaType())
        val result = api.json(api.request("/api/hermes/sessions/$id").newBuilder().patch(body).build())
        val renamed = result.getJSONObject("session").optString("title")
        sessions = sessions.map { if (it.optString("id") == id) JSONObject(it.toString()).put("title", renamed) else it }
        if (selectedId == id) title = renamed
        onDone()
    }

    fun deleteSession(id: String, onDone: () -> Unit) = launch {
        if ((runId != null && runSession == id) || (hasPendingSubmission && prefs.getString("pendingSession", null) == id)) {
            error = "请先停止或核对该会话的任务，再删除会话"
            return@launch
        }
        val result = api.json("/api/hermes/sessions/$id/delete", obj())
        if (!result.optBoolean("deleted")) throw java.io.IOException("Hermes 未删除该会话")
        sessions = sessions.filter { it.optString("id") != id }
        if (selectedId == id) {
            selectedId = null
            title = "Hermes 会话"
            messages = emptyList()
            files = emptyList()
            pendingFiles = emptyList()
            draft = ""
            prefs.edit().remove("session").apply()
        }
        onDone()
    }

    fun send() {
        if (hasPendingSubmission) {
            error = "请先核对上一次提交结果，避免重复创建任务"
            return
        }
        val input = draft.trim().ifBlank { if (pendingFiles.isNotEmpty()) "请查看附件" else "" }
        val session = selectedId ?: return
        if (input.isEmpty() || runId != null || submitting) return
        val key = UUID.randomUUID().toString()
        prefs
            .edit()
            .putString("pendingKey", key)
            .putString("pendingInput", input)
            .putString("pendingSession", session)
            .putString(
                "pendingAttachments",
                org.json.JSONArray(pendingFiles.map { it.getString("id") }).toString(),
            )
            .apply()
        submit(key, input, session)
    }

    fun retryPending() {
        val key = prefs.getString("pendingKey", null) ?: return
        val input = prefs.getString("pendingInput", null) ?: return
        val session = prefs.getString("pendingSession", null) ?: return
        if (runId == null && !submitting) submit(key, input, session)
    }

    val hasPendingSubmission
        get() = prefs.contains("pendingKey") && runId == null

    private fun submit(key: String, input: String, session: String) {
        submitting = true
        error = null
        if (optimisticKey != key) {
            optimisticKey = key
            optimisticText = input
            optimisticSession = session
            optimisticAfter = messages.maxOfOrNull { it.serverId } ?: 0
            optimisticFiles = pendingFiles.toList()
            if (selectedId == session)
                messages = messages + HermesMessage("user", input, attachments = optimisticFiles)
        }
        draft = ""
        launch {
            try {
                val request =
                    api.request(
                            "/api/hermes/runs",
                            obj(
                                "input" to input,
                                "session_id" to session,
                                "attachment_ids" to
                                    org.json.JSONArray(prefs.getString("pendingAttachments", "[]")),
                            ),
                        )
                        .newBuilder()
                        .header("Idempotency-Key", key)
                        .build()
                val response = api.json(request)
                runId = response.getString("run_id")
                prefs.edit().putString("runMessageKey", key).apply()
                pendingFiles = emptyList()
                runSession = session
                draft = ""
                pendingText = ""
                events = emptyList()
                approval = null
                seq = -1
                state = "执行中"
                prefs
                    .edit()
                    .putString("run", runId)
                    .putString("runSession", session)
                    .remove("pendingKey")
                    .remove("pendingInput")
                    .remove("pendingSession")
                    .apply()
                watch()
                loadHistory(session)
            } finally {
                submitting = false
            }
        }
    }

    private fun watch() {
        watching?.cancel()
        streamJob?.cancel()
        val id = runId ?: return
        streamJob =
            viewModelScope.launch {
                while (isActive && runId == id) {
                    try {
                        readEvents(id)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        /* Status polling remains authoritative; reconnect only this GET stream. */
                    }
                    if (runId == id) delay(1500)
                }
            }
        watching = launch {
            while (isActive && runId == id) {
                try {
                    val result = api.json("/api/hermes/runs/$id")
                    state = statusLabel(result.optString("status"))
                    approval = result.optJSONObject("approval")
                    result.optJSONObject("runtime")?.let {
                        runtime = it.optString("provider") + " · " + it.optString("model")
                    }
                    if (
                        result.optString("status") in
                            setOf("completed", "failed", "cancelled", "interrupted")
                    ) {
                        if (result.optString("status") != "completed")
                            error = result.optString("error").ifBlank { state }
                        if (result.optString("output").isNotBlank())
                            pendingText = result.optString("output")
                        runId = null
                        approval = null
                        streamJob?.cancel()
                        prefs.edit().remove("run").remove("runSession").apply()
                        runSession?.let { loadHistory(it) }
                        val returned =
                            files.filter {
                                it.optBoolean("outgoing") &&
                                    it.optString("messageKey") ==
                                        prefs.getString("runMessageKey", "")
                            }
                        val finalMessage = messages.lastOrNull { it.role == "assistant" }
                        if (
                            selectedId == runSession &&
                                returned.isNotEmpty() &&
                                finalMessage != null
                        ) {
                            api.json(
                                "/api/hermes/sessions/${runSession}/messages/${finalMessage.serverId}/attachments",
                                obj(
                                    "ids" to org.json.JSONArray(returned.map { it.getString("id") })
                                ),
                            )
                            messages =
                                messages.map {
                                    if (it.serverId == finalMessage.serverId)
                                        it.copy(attachments = returned)
                                    else it
                                }
                        }
                        refreshSessions()
                        pendingText = ""
                        break
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    error = "暂时无法读取任务状态，可刷新重新连接：${e.message}"
                }
                delay(2000)
            }
        }
    }

    fun reconnect() {
        error = null
        if (runId != null) watch() else refresh()
    }

    private suspend fun readEvents(id: String) {
        val request =
            api.request("/api/hermes/runs/$id/events")
                .newBuilder()
                .apply { if (seq >= 0) header("Last-Event-ID", seq.toString()) }
                .build()
        api.response(request).use { response ->
            if (!response.isSuccessful) return
            withContext(Dispatchers.IO) {
                response.body!!.charStream().buffered().use { reader ->
                    val parser = HermesSseParser()
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        parser.line(line)?.let { json ->
                            val event = JSONObject(json)
                            withContext(Dispatchers.Main) {
                                val n = event.optLong("seq", -1)
                                if (n >= 0 && n <= seq) return@withContext
                                if (n >= 0) seq = n
                                when (event.optString("type", event.optString("event"))) {
                                    "message.delta" -> pendingText += event.optString("delta")
                                    "approval.request" -> approval = event
                                    "tool.started" ->
                                        events =
                                            (events +
                                                    HermesEvent(
                                                        "执行中",
                                                        event.optString("tool"),
                                                        event
                                                            .optString("preview")
                                                            .replace(Regex("\\s+"), " ")
                                                            .take(100),
                                                    ))
                                                .takeLast(20)
                                    "tool.completed",
                                    "tool.failed" -> {
                                        val index =
                                            events.indexOfLast {
                                                it.text == event.optString("tool") &&
                                                    it.type == "执行中"
                                            }
                                        if (index >= 0)
                                            events =
                                                events.mapIndexed { i, item ->
                                                    if (i == index)
                                                        item.copy(
                                                            type =
                                                                if (
                                                                    event.optBoolean("error") ||
                                                                        event.optString("event") ==
                                                                            "tool.failed"
                                                                )
                                                                    "失败"
                                                                else "完成"
                                                        )
                                                    else item
                                                }
                                    }
                                    "message.interim" ->
                                        events =
                                            (events +
                                                    HermesEvent(
                                                        "进度",
                                                        event.optString("text").take(100),
                                                    ))
                                                .takeLast(30)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun stop() = launch {
        runId?.let {
            api.json("/api/hermes/runs/$it/stop", obj())
            state = "正在停止"
        }
    }

    fun approve(choice: String) = launch {
        val id = runId ?: return@launch
        val body = obj("choice" to choice)
        approval
            ?.optString("request_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { body.put("request_id", it) }
        api.json("/api/hermes/runs/$id/approval", body)
        approval = null
    }

    fun fetchModels() = launch {
        modelOptions = api.json("/api/hermes/model-options")
        if (runtime.isBlank())
            runtime = modelOptions.optString("provider") + " · " + modelOptions.optString("model")
    }

    fun refreshFiles() = launch {
        selectedId?.let {
            files = api.json("/api/hermes/sessions/$it/files").array("data").objects()
        }
    }

    fun removePendingFile(id: String) {
        pendingFiles = pendingFiles.filter { it.optString("id") != id }
    }

    fun upload(uri: android.net.Uri, image: Boolean) = launch {
        val session = selectedId ?: throw java.io.IOException("请先选择会话")
        if (pendingFiles.size >= 8) throw java.io.IOException("每条消息最多 8 个附件")
        uploading = true
        try {
            val file =
                withContext(Dispatchers.IO) { prepareHermesUpload(getApplication(), uri, image) }
            try {
                val multipart =
                    okhttp3.MultipartBody.Builder()
                        .setType(okhttp3.MultipartBody.FORM)
                        .addFormDataPart("file", file.name, file.body)
                        .build()
                val request =
                    api.request("/api/hermes/sessions/$session/files")
                        .newBuilder()
                        .post(multipart)
                        .build()
                val result = api.json(request)
                if (selectedId == session) pendingFiles = pendingFiles + result
            } finally {
                file.temporary.delete()
            }
        } finally {
            uploading = false
        }
    }

    fun fetchProviders() = launch { providers = api.json("/api/hermes/providers") }

    fun setModel(
        provider: String,
        model: String,
        global: Boolean,
        onDone: () -> Unit,
        confirm: Boolean = false,
    ) = launch {
        val body = obj("provider" to provider, "model" to model)
        if (global) {
            body.put("scope", "main")
            body.put("confirm_expensive_model", confirm)
            val result = api.json("/api/hermes/default-model", body)
            if (result.optBoolean("confirm_required")) {
                modelWarning = result.optString("confirm_message")
                return@launch
            }
        } else {
            val id = selectedId ?: throw java.io.IOException("请先选择会话")
            api.json("/api/hermes/sessions/$id/model", body)
            runtime = "$provider · $model"
        }
        fetchModels()
        modelWarning = null
        onDone()
    }

    fun saveProvider(body: JSONObject, onDone: () -> Unit) = launch {
        api.json("/api/hermes/providers", body)
        fetchProviders()
        fetchModels()
        onDone()
    }
}

fun newHermesChatName(): String =
    "手机对话" +
        java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))

fun statusLabel(value: String) =
    when (value) {
        "completed" -> "已完成"
        "running",
        "started" -> "执行中"
        "waiting_for_approval" -> "等待审批"
        "stopping" -> "正在停止"
        "cancelled" -> "已停止"
        "failed" -> "执行失败"
        "interrupted" -> "已中断"
        else -> value
    }

class HermesSseParser {
    private val data = StringBuilder()

    fun line(line: String): String? {
        if (line.isEmpty()) {
            val value = data.toString()
            data.setLength(0)
            return value.takeIf { it.isNotBlank() }
        }
        if (line.startsWith("data:")) {
            if (data.isNotEmpty()) data.append('\n')
            data.append(line.substring(5).removePrefix(" "))
        }
        return null
    }
}
