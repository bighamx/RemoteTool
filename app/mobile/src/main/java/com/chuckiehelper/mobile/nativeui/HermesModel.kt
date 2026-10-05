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
    val localKey: String? = null,
    val delivery: String? = null,
    val timestamp: Long? = null,
)

data class HermesEvent(val type: String, val text: String, val detail: String = "")

/** Durable run ids, never a persisted provider secret or automatically replayed prompt. */
class HermesModel(application: Application, deviceId: String, val agent: String = "hermes") : AndroidViewModel(application) {
    val agentName = if (agent == "codex") "Codex" else "Hermes"
    private val root = "/api/$agent"
    private val prefs = application.getSharedPreferences("$agent-$deviceId", 0)
    private lateinit var api: NativeApi
    var sessions by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    var selectedId by mutableStateOf(prefs.getString("session", null))
        private set

    var title by mutableStateOf(agentName)
        private set

    var messages by mutableStateOf<List<HermesMessage>>(emptyList())
        private set

    var events by mutableStateOf<List<HermesEvent>>(emptyList())
        private set

    // 本 run 累计收到的工具/进度事件总数（events 列表只保留最近若干条，计数不能直接用 events.size）
    var eventCount by mutableStateOf(0)
        private set

    private var conversationUi by mutableStateOf(ConversationUiState())
    var draft: String
        get() = conversationUi.entry(selectedId).draft
        set(value) { conversationUi = conversationUi.draft(selectedId, value) }
    private fun setDraft(session: String?, value: String) { conversationUi = conversationUi.draft(session, value) }
    private fun setError(session: String?, value: String?) { conversationUi = conversationUi.error(session, value) }
    private fun setSubmitting(session: String?, value: Boolean) { conversationUi = conversationUi.submitting(session, value) }
    var pendingText by mutableStateOf("")
        private set
    var pendingTextTimestamp by mutableStateOf<Long?>(null)
        private set

    var runId by mutableStateOf(prefs.getString("run", null))
        private set

    var runSession by mutableStateOf(prefs.getString("runSession", null))
        private set

    var state by mutableStateOf("就绪")
        private set

    var error: String?
        get() = conversationUi.entry(selectedId).error
        set(value) { setError(selectedId, value) }
    var loading by mutableStateOf(false)
        private set

    val submitting get() = conversationUi.entry(selectedId).submitting

    var uploading by mutableStateOf(false)
        private set

    var files by mutableStateOf<List<JSONObject>>(emptyList())
        private set

    private var draftFiles by mutableStateOf<Map<String?, List<JSONObject>>>(emptyMap())
    var pendingFiles: List<JSONObject>
        get() = draftFiles[selectedId].orEmpty()
        private set(value) { draftFiles = draftFiles + (selectedId to value) }

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
    var sessionProvider by mutableStateOf("")
        private set
    var sessionModel by mutableStateOf("")
        private set

    var hasMore by mutableStateOf(false)
        private set

    private var watching: Job? = null
    private var historyJob: Job? = null
    private var streamJob: Job? = null
    private var seq = -1L
    private data class LocalSubmission(val pending: PendingAgentSubmission, val existing: Set<Long>, val after: Long, val files: List<JSONObject>)
    private val localSubmissions = mutableMapOf<String, LocalSubmission>()
    private var nextCursor: String? = null
    var accounts by mutableStateOf(JSONObject())
        private set
    var workspaces by mutableStateOf(JSONObject())
        private set
    var loginInfo by mutableStateOf<JSONObject?>(null)
        private set
    var loginVisible by mutableStateOf(false)
        private set
    var usage by mutableStateOf<JSONObject?>(null)
        private set
    var switchingAccount by mutableStateOf(false)
        private set
    var workspaceUsages by mutableStateOf<Map<String, JSONObject>>(emptyMap())
        private set
    var workspaceUsageLoading by mutableStateOf<Set<String>>(emptySet())
        private set
    var projects by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var projectsLoading by mutableStateOf(false)
        private set
    var contextInfo by mutableStateOf<JSONObject?>(null)
        private set
    var asyncQuestion by mutableStateOf<JSONObject?>(null)
        private set
    // 每会话最近一次成功加载的消息列表（内存级，进程内有效），用于 select() 时先回放再刷新。
    private val cachedHistory = mutableMapOf<String, List<HermesMessage>>()
    // 切走时挂到后台的 run（sessionId -> runId）。服务端继续执行，切回对应会话时恢复跟踪。
    private val backgroundRuns = mutableStateMapOf<String, String>().apply { putAll(runCatching {
        JSONObject(prefs.getString("trackedRuns", "{}").orEmpty()).let { rows -> rows.keys().asSequence().associateWith { rows.getString(it) }.toMutableMap() }
    }.getOrDefault(mutableMapOf())) }
    init {
        if (runId != null && runSession != null) backgroundRuns[runSession!!] = runId!!
        runId = selectedId?.let { backgroundRuns.remove(it) }
        runSession = selectedId.takeIf { runId != null }
    }
    private fun saveRuns() {
        val tracked = backgroundRuns.toMutableMap()
        if (runId != null && runSession != null) tracked[runSession!!] = runId!!
        prefs.edit().putString("trackedRuns", JSONObject(tracked as Map<*, *>).toString()).apply()
    }
    private var pendingSubmissions by mutableStateOf(runCatching {
        val rows = JSONObject(prefs.getString("pendingSubmissions", "{}").orEmpty())
        rows.keys().asSequence().associateWith { id ->
            val row = rows.getJSONObject(id)
            PendingAgentSubmission(row.getString("key"), row.getString("input"), row.array("attachments").let { ids -> (0 until ids.length()).map { ids.getString(it) } }, row.optLong("timestamp"), row.optString("questionId").takeIf { it.isNotBlank() && it != "null" })
        }.toMutableMap().apply {
            val session = prefs.getString("pendingSession", null)
            val key = prefs.getString("pendingKey", null)
            if (session != null && key != null && !containsKey(session)) put(session, PendingAgentSubmission(key, prefs.getString("pendingInput", "").orEmpty(), org.json.JSONArray(prefs.getString("pendingAttachments", "[]")).let { ids -> (0 until ids.length()).map { ids.getString(it) } }, prefs.getLong("pendingTimestamp", 0), prefs.getString("answeringQuestion", null).takeIf { prefs.getString("answeringQuestionSession", null) == session }))
        }.toMap()
    }.getOrDefault(emptyMap<String, PendingAgentSubmission>()))
    private fun savePending() {
        val rows = JSONObject()
        pendingSubmissions.forEach { (id, pending) -> rows.put(id, obj("key" to pending.key, "input" to pending.input, "attachments" to org.json.JSONArray(pending.attachmentIds), "timestamp" to pending.timestamp, "questionId" to pending.questionId)) }
        prefs.edit().putString("pendingSubmissions", rows.toString()).remove("pendingKey").remove("pendingInput").remove("pendingSession").remove("pendingAttachments").remove("pendingTimestamp").apply()
    }
    private fun removePending(session: String) { pendingSubmissions = pendingSubmissions - session; savePending() }
    var scrollToLatestRequest by mutableStateOf(0L)
        private set
    private var readConnectionError: String? = null
    private var flushedStreamPrefix = ""
    private var steering = runCatching {
        org.json.JSONArray(prefs.getString("steeringMessages", "[]")).objects().map { row ->
            val ids = row.array("existingIds")
            SteeringMessage(row.getString("key"), row.getString("session"), row.getString("text"),
                (0 until ids.length()).map { ids.getLong(it) }.toSet(), row.optLong("anchor"), row.optString("delivery", "发送状态待核对"), parseMessageTimestamp(row.opt("timestamp")), row.array("attachments").objects())
        }
    }.getOrDefault(emptyList())
    private var narrations = runCatching {
        org.json.JSONArray(prefs.getString("assistantNarrations", "[]")).objects().map {
            AssistantNarration(it.getString("key"), it.getString("session"), it.getString("text"), it.optLong("anchor"), it.optString("userText"), it.getLong("timestamp"))
        }
    }.getOrDefault(emptyList())
    private fun saveNarrations() {
        prefs.edit().putString("assistantNarrations", org.json.JSONArray(narrations.map {
            obj("key" to it.key, "session" to it.session, "text" to it.text, "anchor" to it.anchor, "userText" to it.userText, "timestamp" to it.timestamp)
        }).toString()).apply()
    }
    private fun showToolNarration(run: String, tool: String, preview: String, timestamp: Any?) {
        val session = runSession ?: return
        val text = terminalNarration(tool, preview) ?: return
        if (narrations.any { it.session == session && it.key.startsWith("narration-$run-") && it.text == text }) return
        if (pendingText.split("\n\n").any { it.trim() == text.trim() }) return
        val user = messages.lastOrNull { it.role == "user" } ?: return
        val note = AssistantNarration("narration-$run-${UUID.randomUUID()}", session, text, user.serverId, user.text,
            parseMessageTimestamp(timestamp) ?: System.currentTimeMillis())
        narrations = narrations + note; saveNarrations()
        messages = mergeAssistantNarrations(messages, listOf(note))
        cachedHistory[session] = messages
    }
    private fun saveSteering() {
        prefs.edit().putString("steeringMessages", org.json.JSONArray(steering.map { row ->
            obj("key" to row.key, "session" to row.session, "text" to row.text, "existingIds" to org.json.JSONArray(row.existingIds.toList()),
                "anchor" to row.anchor, "delivery" to if (row.delivery == "正在发送") "发送状态待核对" else row.delivery, "timestamp" to row.timestamp, "attachments" to org.json.JSONArray(row.attachments))
        }).toString()).apply()
    }
    private fun updateSteering(key: String, delivery: String) {
        steering = steering.map { if (it.key == key) it.copy(delivery = delivery) else it }
        messages = messages.map { if (it.localKey == key) it.copy(delivery = delivery) else it }
        saveSteering()
    }
    fun pollContext() = viewModelScope.launch {
        val id = selectedId ?: return@launch
        try {
            val result = api.json("$root/sessions/${q(id)}/context")
            if (selectedId != id) return@launch
            contextInfo = result.optJSONObject("context") ?: result
            if (agent == "codex") asyncQuestion = visibleQuestion(result.optJSONObject("question"), id)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    private fun visibleQuestion(question: JSONObject?, session: String): JSONObject? = question?.takeUnless {
        val id = it.optString("request_id")
        prefs.getStringSet("answeredQuestions:$session", emptySet())!!.contains(id) ||
            (prefs.getString("answeringQuestion:$session", null) == id) || pendingSubmissions[session]?.questionId == id
    }
    private fun questionAccepted(session: String) {
        val question = prefs.getString("answeringQuestion:$session", null) ?: return
        val answered = prefs.getStringSet("answeredQuestions:$session", emptySet())!!.toMutableSet()
        answered.add(question)
        prefs.edit().putStringSet("answeredQuestions:$session", (answered.filter { it != question }.takeLast(63) + question).toSet()).remove("answeringQuestion:$session").apply()
        if (selectedId == session && asyncQuestion?.optString("request_id") == question) asyncQuestion = null
    }
    fun answerAsyncQuestion(request: JSONObject, answers: JSONObject): Boolean {
        val session = selectedId ?: return false
        val accepted = sendInput(codexQuestionReply(request, answers), questionId = request.optString("request_id"))
        if (accepted && selectedId == session) asyncQuestion = null
        return accepted
    }
    fun compact() = launch {
        val id = selectedId ?: return@launch
        if (runId != null || submitting || hasPendingSubmission) throw java.io.IOException("请先结束或核对当前任务，再压缩上下文")
        setSubmitting(id, true)
        try {
            val request = api.request("$root/sessions/${q(id)}/compact", obj()).newBuilder().header("Idempotency-Key", UUID.randomUUID().toString()).build()
            val result = api.json(request)
            if (selectedId != id) {
                backgroundRuns[id] = result.getString("run_id"); saveRuns()
                return@launch
            }
            runId = result.getString("run_id"); runSession = id; seq = -1; events = emptyList(); eventCount = 0; pendingText = ""; approval = null; state = "正在压缩上下文"
            prefs.edit().putString("run", runId).putString("runSession", id).remove("runMessageKey").apply()
            saveRuns(); watch()
        } finally { setSubmitting(id, false) }
    }
    fun fetchProjects() = launch {
        projectsLoading = true; error = null
        try { projects = api.json("$root/projects").array("data").objects() }
        finally { projectsLoading = false }
    }

    fun bind(value: NativeApi) {
        if (this::api.isInitialized && api.base == value.base) return
        api = value.withReadPolicy(noRetry = true, onReadSuccess = {
            if (api.base == value.base) {
                if (error == readConnectionError) error = null
                readConnectionError = null
            }
        })
        watching?.cancel()
        streamJob?.cancel()
        launch {
            capabilities = api.json("$root/capabilities")
            if (agent == "hermes" && capabilities.optJSONObject("features")?.optBoolean("run_submission") != true)
                throw java.io.IOException("当前 $agentName 不支持可恢复任务接口")
            refreshSessions()
            if (agent == "codex") reconcilePendingNow()
            fetchModels()
            if (agent == "codex") fetchAccountsNow()
            selectedId?.let { loadHistory(it); loadSelection(it) }
            if (runId != null) watch()
            else if (hasPendingSubmission && agent == "hermes") error = "上次任务提交结果尚未确认，请使用原标识核对并重试"
        }
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        val originatingSession = selectedId
        return viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                setError(originatingSession, connectionFailureMessage(e))
                if (e is ReadConnectionFailure && selectedId == originatingSession) readConnectionError = error
            }
        }
    }

    fun refresh() = launch {
        refreshSessions()
        selectedId?.let { loadHistory(it) }
    }

    private suspend fun refreshSessions(offset: Int = 0) {
        loading = true
        try {
            val result = api.json(if (agent == "codex") "$root/sessions" +
                (if (offset > 0 && nextCursor != null) "?cursor=${q(nextCursor!!)}" else "")
                else "$root/sessions?offset=$offset")
            sessions = mergeSessionPage(
                if (offset == 0) emptyList() else sessions,
                result.array("data").objects(),
            ) { it.optString("id") }
            hasMore = result.optBoolean("has_more")
            nextCursor = result.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
        } finally {
            loading = false
        }
    }

    fun moreSessions() = launch { refreshSessions(sessions.size) }
    fun sessionActivity(session: JSONObject): String? {
        val id = session.optString("id")
        if ((runId != null && runSession == id) || backgroundRuns.containsKey(id)) return if (approval != null && runSession == id) "等待确认" else "运行中"
        val status = session.optJSONObject("status")
        val type = status?.optString("type") ?: session.optString("status")
        if (type in listOf("active", "running", "started", "in_progress")) {
            val flags = status?.array("activeFlags")?.let { (0 until it.length()).map { index -> it.optString(index) } }.orEmpty()
            return if (flags.any { it in listOf("waitingOnApproval", "waitingOnUserInput") }) "等待确认" else "运行中"
        }
        if (hasPendingFor(id)) return "待核对"
        return null
    }
    fun hasPendingFor(id: String) = pendingSubmissions.containsKey(id)
    fun clearPendingRecord(restoreDraft: Boolean = true) {
        val session = selectedId ?: return
        val pending = pendingSubmissions[session] ?: return
        removePending(session)
        prefs.edit().remove("answeringQuestion:$session").apply()
        localSubmissions.remove(session)
        if (restoreDraft && draft.isBlank() && pending.questionId == null) draft = pending.input
        error = null; state = ""
        refresh()
    }
    fun reconcilePending() = launch { reconcilePendingNow() }
    private suspend fun reconcilePendingNow(session: String? = selectedId) {
        if (agent != "codex" || session == null) return
        val pending = pendingSubmissions[session] ?: return
        val result = api.json("$root/runs/lookup?key=${q(pending.key)}")
        if (pendingSubmissions[session]?.key != pending.key || !result.optBoolean("found")) return
        when (result.optString("status")) {
            "started", "submitting" -> {
                val id = result.getString("run_id")
                removePending(session)
                if (selectedId == session) {
                    runId = id; runSession = session
                    seq = -1; events = emptyList(); eventCount = 0; approval = null; pendingText = ""; state = "执行中"
                    prefs.edit().putString("run", id).putString("runSession", session).putString("runMessageKey", pending.key).apply()
                    watch()
                } else backgroundRuns[session] = id
                saveRuns()
            }
            "completed" -> { removePending(session); if (selectedId == session) loadHistory(session) }
            "failed", "interrupted" -> {
                removePending(session)
                if (conversationUi.entry(session).draft.isBlank() && pending.questionId == null) setDraft(session, pending.input)
            }
        }
    }
    fun pollSessionStates() = viewModelScope.launch {
        try {
            if (agent == "codex") {
                val latest = mergeSessionPage(emptyList(), api.json("$root/sessions").array("data").objects()) { it.optString("id") }
                val indexed = latest.associateBy { it.optString("id") }
                val known = sessions.map { it.optString("id") }.toSet()
                sessions = latest.filter { it.optString("id") !in known } + sessions.map { indexed[it.optString("id")] ?: it }
                reconcilePendingNow()
            }
            for ((session, id) in backgroundRuns.toMap()) {
                try {
                    val result = api.json("$root/runs/$id")
                    if (result.optString("status") in setOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown") && backgroundRuns[session] == id) {
                        backgroundRuns.remove(session); saveRuns()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: ApiRequestFailure) {
                    if (e.status == 404 && backgroundRuns[session] == id) { backgroundRuns.remove(session); saveRuns() }
                } catch (_: Exception) { }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }

    private suspend fun fetchAccountsNow() {
        accounts = api.json("$root/accounts")
        workspaces = api.json("$root/workspaces")
        fetchWorkspaceUsages()
    }
    fun fetchWorkspaceUsages(force: Boolean = false) {
        if (agent != "codex") return
        workspaces.array("data").objects().forEach { row ->
            val id = row.optString("id")
            if (id.isBlank() || id in workspaceUsageLoading) return@forEach
            workspaceUsageLoading = workspaceUsageLoading + id
            viewModelScope.launch {
                try {
                    val result = api.json("$root/workspaces/${q(id)}/usage" + if (force) "?refresh=true" else "")
                    if (result.optString("workspace_id") == id) workspaceUsages = workspaceUsages + (id to result)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { workspaceUsages = workspaceUsages + (id to obj("available" to false, "error" to "暂时无法读取用量，请刷新重试")) }
                finally { workspaceUsageLoading = workspaceUsageLoading - id }
            }
        }
    }
    fun fetchAccounts() = launch { error = null; fetchAccountsNow() }

    private suspend fun resetAccountView() {
        historyJob?.cancel()
        selectedId = null
        contextInfo = null; asyncQuestion = null
        sessionProvider = ""; sessionModel = ""
        messages = emptyList()
        files = emptyList()
        pendingFiles = emptyList()
        draft = ""
        title = agentName
        prefs.edit().remove("session").apply()
        fetchAccountsNow()
        refreshSessions()
        fetchModels()
    }
    fun switchAccount(id: String, workspace: Boolean, onDone: () -> Unit) = launch {
        if (switchingAccount) return@launch
        switchingAccount = true
        error = null
        try {
            api.json("$root/${if (workspace) "workspaces" else "accounts"}/$id/use", obj())
            watching?.cancel(); streamJob?.cancel()
            runId = null; runSession = null; approval = null; usage = null
            backgroundRuns.clear(); saveRuns(); pendingSubmissions = emptyMap(); savePending(); conversationUi = ConversationUiState(); draftFiles = emptyMap(); localSubmissions.clear()
            prefs.edit().remove("run").remove("runSession").remove("pendingKey").remove("pendingInput").remove("pendingSession").apply()
            resetAccountView()
            fetchUsage()
            onDone()
        } finally { switchingAccount = false }
    }
    fun beginAccountLogin(name: String, workspace: Boolean, onStarted: () -> Unit) = launch {
        if (runId != null || hasPendingSubmission) throw java.io.IOException("请先停止或核对当前任务再登录")
        loginInfo = api.json(if (workspace) "$root/workspaces" else "$root/accounts/login", obj("name" to name))
        onStarted()
        while (loginInfo?.optBoolean("completed") != true) {
            delay(2000)
            loginInfo = api.json("$root/accounts/login-status")
        }
        if (loginInfo?.optBoolean("success") == true) fetchAccountsNow()
        else error = loginInfo?.optString("error").orEmpty().ifBlank { "登录未完成" }
    }
    fun dismissLogin() { loginVisible = false }
    fun showLogin() { loginVisible = true }
    fun fetchUsage() = viewModelScope.launch {
        try { usage = api.json("$root/usage") }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { usage = null }
    }
    fun importCurrentAccount() = launch { api.json("$root/accounts/import", obj()); fetchAccountsNow() }
    fun removeAccount(id: String) = launch { api.json("$root/accounts/$id/remove", obj()); fetchAccountsNow() }
    fun answerQuestions(answers: JSONObject) = launch {
        if (submitting) return@launch
        val id = runId ?: return@launch
        val session = selectedId
        val request = approval?.optString("request_id") ?: return@launch
        setSubmitting(session, true); setError(session, null)
        try {
            api.json("$root/runs/$id/approval", obj("request_id" to request, "answers" to answers))
            if (selectedId == session && runId == id && approval?.optString("request_id") == request) approval = null
        } finally { setSubmitting(session, false) }
    }

    fun select(session: JSONObject) {
        val id = session.getString("id")
        // 切换会话时把当前 run 挂到后台（服务端继续跑），切回时恢复跟踪。
        // runId/runSession 语义 = 「当前选中会话的 run」，不再是全局单值锁。
        if (runId != null && runSession != null && runSession != id) {
            backgroundRuns[runSession!!] = runId!!
            runId = null; runSession = null; approval = null
            pendingText = ""; flushedStreamPrefix = ""; pendingTextTimestamp = null
            events = emptyList(); eventCount = 0
            watching?.cancel(); streamJob?.cancel()
        }
        if (selectedId != id) {
            runSession = null
            seq = -1; events = emptyList(); eventCount = 0
            pendingText = ""; pendingTextTimestamp = null; flushedStreamPrefix = ""
            state = "就绪"; readConnectionError = null; files = emptyList()
            prefs.edit().remove("run").remove("runSession").apply()
        }
        selectedId = id
        title = session.optString("title").ifBlank { "未命名会话" }
        prefs.edit().putString("session", selectedId).apply()
        historyJob?.cancel()
        sessionProvider = ""; sessionModel = ""
        contextInfo = null; asyncQuestion = null
        // 先回放上一次该会话的消息（如有缓存），网络刷新到位后替换 —— 消除「返回再进白屏等待」。
        cachedHistory[selectedId]?.let { cached ->
            messages = cached
        } ?: run { messages = emptyList() }
        // 恢复该会话的后台 run 跟踪
        backgroundRuns.remove(id)?.let { resumed ->
            runId = resumed; runSession = id
            prefs.edit().putString("run", resumed).putString("runSession", id).apply()
            watch()
        }
        saveRuns()
        historyJob = launch { reconcilePendingNow(id); loadHistory(id); loadSelection(id) }
    }

    /** 多端共享：本地没有跟踪时，向服务端查询该会话是否有其它设备发起的活跃 run，有则以观察者身份挂载。 */
    private suspend fun attachServerActiveRun(id: String) {
        if (runId != null || submitting) return
        val connection = api
        try {
            val result = connection.json("$root/sessions/${q(id)}/active-run")
            if (selectedId != id || api !== connection || runId != null) return
            val serverRun = result.optString("run_id").takeIf { it.isNotBlank() && it != "null" } ?: return
            runId = serverRun; runSession = id
            seq = -1; events = emptyList(); eventCount = 0; approval = null; pendingText = ""
            prefs.edit().putString("run", serverRun).putString("runSession", id).apply()
            state = "执行中"
            saveRuns(); watch()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    private suspend fun loadSelection(id: String) {
        val connection = api
        val result = connection.json("$root/sessions/$id").optJSONObject("session") ?: return
        if (selectedId != id || api !== connection) return
        val config = result.optJSONObject("model_config")
        sessionProvider = result.optString("provider").takeIf { it.isNotBlank() && it != "null" }
            ?: config?.optString("requested_provider")?.takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        sessionModel = config?.optString("requested_model")?.takeIf { it.isNotBlank() && it != "null" }
            ?: result.optString("model").takeIf { it.isNotBlank() && it != "null" }.orEmpty()
        contextInfo = result.optJSONObject("context")
        asyncQuestion = visibleQuestion(result.optJSONObject("question"), id)
        pollContext()
        attachServerActiveRun(id)
    }

    private suspend fun loadHistory(id: String) {
        val connection = api
        val response = connection.json("$root/sessions/$id/messages")
        if (id != selectedId || api !== connection) return
        messages =
            response.array("data").objects().mapNotNull { row ->
                val role = row.optString("role")
                val text =
                    row.optString("content").let {
                        if (role == "user") agentUserMessageText(agent, it) else it
                    }
                if (role in listOf("user", "assistant") && text.isNotBlank() && text != "null")
                    HermesMessage(role, text, row.optLong("id"), row.array("attachments").objects(), timestamp =
                        parseMessageTimestamp(row.opt("timestamp")) ?: parseMessageTimestamp(row.opt("created_at")))
                else null
            }
        val reconciliation = reconcileSteeringMessages(messages, steering.filter { it.session == id })
        for ((sent, confirmed) in reconciliation.second) {
            if (sent.attachments.isNotEmpty()) {
                connection.json("$root/sessions/$id/messages/${confirmed.serverId}/attachments",
                    obj("ids" to org.json.JSONArray(sent.attachments.map { it.getString("id") })))
                if (selectedId != id || api !== connection) return
                messages = messages.map { if (it.serverId == confirmed.serverId) it.copy(attachments = sent.attachments) else it }
            }
        }
        steering = steering.filter { it.session != id } + reconciliation.first
        saveSteering()
        messages = mergeSteeringMessages(messages, steering.filter { it.session == id })
        if (messages.isNotEmpty()) cachedHistory[id] = messages
        localSubmissions[id]?.let { local ->
            val acknowledged = messages.firstOrNull {
                it.role == "user" && (if (agent == "codex") it.serverId !in local.existing else it.serverId > local.after) &&
                    it.text.replace("\r\n", "\n").trim() == local.pending.input.replace("\r\n", "\n").trim()
            }
            if (acknowledged != null) {
                if (local.files.isNotEmpty()) {
                    connection.json("$root/sessions/$id/messages/${acknowledged.serverId}/attachments",
                        obj("ids" to org.json.JSONArray(local.files.map { it.getString("id") })))
                    if (selectedId != id || api !== connection) return
                    messages = messages.map { if (it.serverId == acknowledged.serverId) it.copy(attachments = local.files) else it }
                }
                localSubmissions.remove(id)
            } else messages = messages + HermesMessage("user", local.pending.input, attachments = local.files,
                localKey = local.pending.key, delivery = if (hasPendingFor(id)) "正在发送" else "已送达", timestamp = local.pending.timestamp)
        }
        messages = mergeAssistantNarrations(messages, narrations.filter { it.session == id })
        if (messages.isNotEmpty()) cachedHistory[id] = messages
        sessions
            .find { it.optString("id") == id }
            ?.let { title = it.optString("title").ifBlank { "未命名会话" } }
        if (selectedId == id) {
            val loadedFiles = connection.json("$root/sessions/$id/files").array("data").objects()
            if (selectedId == id && api === connection) files = loadedFiles
        }
    }

    fun newSession(name: String, onDone: () -> Unit = {}, project: JSONObject? = null) = launch {
        if (submitting) return@launch
        val originatingSession = selectedId
        setSubmitting(originatingSession, true)
        try {
            val body = obj("title" to name.ifBlank { newHermesChatName() })
            if (agent == "codex") project?.keys()?.forEach { key -> body.put(key, project.get(key)) }
            val result =
                api.json(
                    "$root/sessions",
                    body,
                )
            val session = result.getJSONObject("session")
            select(session)
            onDone()
            refreshSessions()
        } finally {
            setSubmitting(originatingSession, false)
        }
    }

    fun renameSession(id: String, name: String, onDone: () -> Unit) = launch {
        val body = obj("title" to name.trim()).toString().toRequestBody("application/json".toMediaType())
        val result = api.json(api.request("$root/sessions/$id").newBuilder().patch(body).build())
        val renamed = result.getJSONObject("session").optString("title")
        sessions = sessions.map { if (it.optString("id") == id) JSONObject(it.toString()).put("title", renamed) else it }
        if (selectedId == id) title = renamed
        onDone()
    }

    fun deleteSession(id: String, onDone: () -> Unit) = launch {
        if ((runId != null && runSession == id) || backgroundRuns.containsKey(id) || hasPendingFor(id)) {
            error = "请先停止或核对该会话的任务，再删除会话"
            return@launch
        }
        val result = api.json("$root/sessions/$id/delete", obj())
        if (!result.optBoolean("deleted")) throw java.io.IOException("$agentName 未删除该会话")
        sessions = sessions.filter { it.optString("id") != id }
        cachedHistory.remove(id)
        narrations = narrations.filterNot { it.session == id }; saveNarrations()
        localSubmissions.remove(id)
        draftFiles = draftFiles - id
        conversationUi = conversationUi.forget(id)
        steering = steering.filterNot { it.session == id }
        saveSteering()
        if (selectedId == id) {
            selectedId = null
            contextInfo = null; asyncQuestion = null
            sessionProvider = ""; sessionModel = ""
            title = "$agentName 会话"
            messages = emptyList()
            files = emptyList()
            pendingFiles = emptyList()
            draft = ""
            prefs.edit().remove("session").apply()
        }
        onDone()
    }

    fun send(): Boolean = sendInput(draft.trim().ifBlank { if (pendingFiles.isNotEmpty()) "请查看附件" else "" })

    private fun sendInput(input: String, questionId: String? = null): Boolean {
        val session = selectedId ?: return false
        if (hasPendingSubmission) { error = "请先核对上一次提交结果，避免重复创建任务"; return false }
        if (input.isBlank() || submitting) return false
        val isQuestion = questionId != null
        if (runId != null && runSession == session) {
            val id = runId!!
            if (id.startsWith("hcompact_") || state == "正在压缩上下文") { error = "请等待压缩完成后发送"; return false }
            val attached = if (isQuestion) emptyList() else pendingFiles.toList()
            if (attached.isNotEmpty() && !(if (agent == "codex") capabilities.optBoolean("attachment_steering")
                    else capabilities.optJSONObject("chuckie_features")?.optBoolean("attachment_steering") == true)) {
                error = "当前服务端尚未更新附件插话接口，附件和文字已保留"
                return false
            }
            if (!isQuestion) draft = ""
            error = null
            setSubmitting(session, true)
            val steerKey = UUID.randomUUID().toString()
            val record = SteeringMessage(steerKey, session, input, messages.map { it.serverId }.filter { it > 0 }.toSet(), messages.lastOrNull { it.serverId > 0 }?.serverId ?: 0, timestamp = System.currentTimeMillis(), attachments = attached)
            steering = steering + record; saveSteering()
            if (pendingText.isNotBlank()) {
                messages = messages + HermesMessage("assistant", pendingText, localKey = "stream-$steerKey", timestamp = pendingTextTimestamp)
                flushedStreamPrefix += pendingText; pendingText = ""; pendingTextTimestamp = null
            }
            messages = messages + HermesMessage("user", input, attachments = attached, localKey = steerKey, delivery = "正在发送", timestamp = record.timestamp)
            scrollToLatestRequest++
            if (isQuestion) prefs.edit().putString("answeringQuestion:$session", questionId).apply()
            launch {
                try {
                    val request = api.request("$root/runs/$id/steer", obj("input" to input, "session_id" to session, "attachment_ids" to org.json.JSONArray(attached.map { it.getString("id") }))).newBuilder().header("Idempotency-Key", steerKey).build()
                    api.json(request)
                    questionAccepted(session); updateSteering(steerKey, "已送达")
                    val ids = attached.map { it.getString("id") }.toSet()
                    draftFiles = draftFiles + (session to draftFiles[session].orEmpty().filterNot { it.optString("id") in ids })
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    setError(session, connectionFailureMessage(e))
                    updateSteering(steerKey, if (e is ApiRequestFailure) "发送失败" else "发送状态待核对")
                    if (!isQuestion && conversationUi.entry(session).draft.isBlank()) setDraft(session, input)
                    if (isQuestion) prefs.edit().remove("answeringQuestion:$session").apply()
                    if (selectedId == session) pollContext()
                } finally { setSubmitting(session, false) }
            }
            return true
        }
        val pending = PendingAgentSubmission(UUID.randomUUID().toString(), input,
            if (isQuestion) emptyList() else pendingFiles.map { it.getString("id") }, System.currentTimeMillis(), questionId)
        pendingSubmissions = pendingSubmissions + (session to pending); savePending()
        if (isQuestion) prefs.edit().putString("answeringQuestion:$session", questionId).apply()
        submit(pending, session)
        return true
    }

    fun retryPending() {
        val session = selectedId ?: return
        val pending = pendingSubmissions[session] ?: return
        if (runId == null && !submitting) submit(pending, session)
    }
    val hasPendingSubmission get() = selectedId?.let { hasPendingFor(it) } == true && runId == null

    private fun submit(pending: PendingAgentSubmission, session: String) {
        val key = pending.key; val input = pending.input
        val provider = sessionProvider; val model = sessionModel
        val attached = pendingFiles.filter { it.optString("id") in pending.attachmentIds }
        setSubmitting(session, true); setError(session, null)
        if (localSubmissions[session]?.pending?.key != key) {
            localSubmissions[session] = LocalSubmission(pending, messages.map { it.serverId }.toSet(), messages.maxOfOrNull { it.serverId } ?: 0, attached)
            if (selectedId == session) {
                messages = messages + HermesMessage("user", input, attachments = attached, localKey = key, delivery = "正在发送", timestamp = pending.timestamp)
                scrollToLatestRequest++
            }
        }
        if (pending.questionId == null) setDraft(session, "")
        launch {
            try {
                val payload = obj("input" to input, "session_id" to session,
                    "account_id" to if (agent == "codex") accounts.optString("current").takeIf { it.isNotBlank() && it != "null" } else null,
                    "attachment_ids" to org.json.JSONArray(pending.attachmentIds))
                if (agent == "codex" && pending.questionId != null) payload.put("question_reply_id", pending.questionId)
                if (agent == "hermes") {
                    provider.takeIf { it.isNotBlank() }?.let { payload.put("provider", it) }
                    model.takeIf { it.isNotBlank() }?.let { payload.put("model", it) }
                }
                val request = api.request("$root/runs", payload).newBuilder().header("Idempotency-Key", key).build()
                val response = api.json(request)
                val startedRun = response.getString("run_id")
                questionAccepted(session); removePending(session)
                if (selectedId == session) {
                    runId = startedRun; runSession = session
                    prefs.edit().putString("runMessageKey", key).putString("run", startedRun).putString("runSession", session).apply()
                    pendingFiles = pendingFiles.filterNot { it.optString("id") in pending.attachmentIds }
                    pendingText = ""; flushedStreamPrefix = ""; pendingTextTimestamp = null
                    events = emptyList(); eventCount = 0; approval = null; seq = -1; state = "执行中"
                    watch(); loadHistory(session)
                } else backgroundRuns[session] = startedRun
                saveRuns()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is ApiRequestFailure) {
                    removePending(session)
                    prefs.edit().remove("answeringQuestion:$session").apply()
                    if (pending.questionId == null && conversationUi.entry(session).draft.isBlank()) setDraft(session, input)
                    localSubmissions.remove(session)
                    if (selectedId == session) {
                        messages = messages.map { if (it.localKey == key) it.copy(delivery = "发送失败") else it }
                        pollContext()
                    }
                } else if (agent == "codex") try { reconcilePendingNow(session) } catch (_: Exception) { }
                setError(session, connectionFailureMessage(e))
            } finally { setSubmitting(session, false) }
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
                    val result = api.json("$root/runs/$id")
                    currentCoroutineContext().ensureActive()
                    if (runId != id) return@launch
                    state = if (result.optString("kind") == "compact" && result.optString("status") == "started") "正在压缩上下文" else statusLabel(result.optString("status"))
                    approval = result.optJSONObject("approval")
                    result.optJSONObject("runtime")?.let {
                        runtime = it.optString("provider") + " · " + it.optString("model")
                        if (selectedId == runSession) { sessionProvider = it.optString("provider"); sessionModel = it.optString("model") }
                    }
                    if (
                        result.optString("status") in
                            setOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown")
                    ) {
                        if (result.optString("status") != "completed")
                            error = result.optString("error").ifBlank { state }
                        if (result.optString("output").isNotBlank())
                            pendingText = result.optString("output").removePrefix(flushedStreamPrefix)
                        runId = null
                        backgroundRuns.values.remove(id); saveRuns()
                        approval = null
                        streamJob?.cancel()
                        prefs.edit().remove("run").remove("runSession").apply()
                        val finishedSession = runSession
                        finishedSession?.let { loadHistory(it) }
                        currentCoroutineContext().ensureActive()
                        if (selectedId != finishedSession || runId != null) return@launch
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
                                "$root/sessions/${runSession}/messages/${finalMessage.serverId}/attachments",
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
                    currentCoroutineContext().ensureActive()
                    // run 在服务端已不存在（404 run_not_found）：视为任务已终结，清除跟踪而不是无限重试刷错误。
                    if (e is ApiRequestFailure && e.status == 404) {
                        runId = null; approval = null
                        streamJob?.cancel()
                        backgroundRuns.values.remove(id); saveRuns()
                        prefs.edit().remove("run").remove("runSession").apply()
                        state = "任务记录已不存在，请核对会话历史"
                        runSession?.let { loadHistory(it) }
                        refreshSessions()
                        break
                    }
                    error = "暂时无法读取任务状态：${connectionFailureMessage(e)}"
                    if (e is ReadConnectionFailure) readConnectionError = error
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
            api.request("$root/runs/$id/events")
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
                                if (runId != id) return@withContext
                                val n = event.optLong("seq", -1)
                                if (n >= 0 && n <= seq) return@withContext
                                if (n >= 0) seq = n
                                when (event.optString("type", event.optString("event"))) {
                                    "message.delta" -> {
                                        if (pendingTextTimestamp == null) pendingTextTimestamp = parseMessageTimestamp(event.opt("timestamp")) ?: System.currentTimeMillis()
                                        pendingText += event.optString("delta")
                                    }
                                    "approval.request" -> approval = event
                                    "tool.started" -> {
                                        showToolNarration(id, event.optString("tool"), event.optString("preview"), event.opt("timestamp"))
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
                                        eventCount++
                                    }
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
                                    "message.interim" -> {
                                        if (pendingTextTimestamp == null) pendingTextTimestamp = parseMessageTimestamp(event.opt("timestamp")) ?: System.currentTimeMillis()
                                        val text = event.optString("text")
                                        // 途中的旁白消息直接进聊天流（用户不再长时间看不到任何输出）；
                                        // 进度区保留一份便于回看。
                                        // 去重：同一段文本若已被 delta 流或已 flush 的前缀覆盖（旁白与正式输出
                                        // 内容相同时网关会推两遍），只进进度区，不再追加聊天流。
                                        if (text.isNotBlank()) {
                                            val alreadyShown = pendingText.contains(text) || flushedStreamPrefix.contains(text) ||
                                                messages.lastOrNull { it.role == "assistant" }?.text?.contains(text) == true
                                            if (!alreadyShown)
                                                pendingText += if (pendingText.isBlank()) text else "\n\n$text"
                                            events = (events + HermesEvent("进度", text.take(100))).takeLast(30)
                                            eventCount++
                                        }
                                    }
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
            api.json("$root/runs/$it/stop", obj())
            state = "正在停止"
        }
    }

    fun approve(choice: String) = launch {
        if (submitting) return@launch
        val id = runId ?: return@launch
        val body = obj("choice" to choice)
        approval
            ?.optString("request_id")
            ?.takeIf { it.isNotBlank() }
            ?.let { body.put("request_id", it) }
        val session = selectedId
        val requestId = approval?.optString("request_id")
        setSubmitting(session, true); setError(session, null)
        try {
            api.json("$root/runs/$id/approval", body)
            if (selectedId == session && runId == id && approval?.optString("request_id") == requestId) approval = null
        } finally { setSubmitting(session, false) }
    }

    fun fetchModels() = launch {
        modelOptions = api.json("$root/model-options")
        if (runtime.isBlank())
            runtime = modelOptions.optString("provider") + " · " + modelOptions.optString("model")
    }

    fun refreshFiles() = launch {
        selectedId?.let {
            files = api.json("$root/sessions/$it/files").array("data").objects()
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
                    api.request("$root/sessions/$session/files")
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

    fun fetchProviders() = launch { providers = api.json("$root/providers") }

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
            val result = api.json("$root/default-model", body)
            if (result.optBoolean("confirm_required")) {
                modelWarning = result.optString("confirm_message")
                return@launch
            }
        } else {
            val id = selectedId ?: throw java.io.IOException("请先选择会话")
            api.json("$root/sessions/$id/model", body)
            runtime = "$provider · $model"
            sessionProvider = provider; sessionModel = model
        }
        fetchModels()
        modelWarning = null
        onDone()
    }

    fun saveProvider(body: JSONObject, onDone: () -> Unit) = launch {
        api.json("$root/providers", body)
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
