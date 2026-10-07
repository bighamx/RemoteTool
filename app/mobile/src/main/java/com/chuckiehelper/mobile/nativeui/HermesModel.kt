package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.channels.Channel
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
    val narration: Boolean = false,
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
    private val sessionRefreshMutex = Mutex()
    private val historyReadMutex = Mutex()
    private var sessionActivities by mutableStateOf<Map<String, SessionActivityEvidence>>(emptyMap())
    private var activityNow by mutableLongStateOf(activityClock())
    private fun activityClock() = System.nanoTime() / 1_000_000
    fun tickActivity() {
        if (runId != null || externalActivity?.optBoolean("running") == true || sessionActivities.values.any { it.state in setOf("active", "waiting", "submitting") })
            activityNow = activityClock()
    }
    val hasKnownActivity get() = runId != null || backgroundRuns.isNotEmpty() || externalRunning ||
        sessionActivities.values.any { it.label(activityNow) in setOf("运行中", "等待确认", "正在提交") }
    private fun noteSessionActivities(rows: List<JSONObject>, requestedAt: Long) {
        rows.forEach { row ->
            val id = row.optString("id")
            val evidence = sessionActivityEvidence(row, requestedAt)
            // Hermes history rows often omit status; absence is not an idle observation.
            if (id.isNotBlank() && evidence.state != "unknown") sessionActivities = updateSessionActivity(sessionActivities, id, evidence)
        }
        activityNow = activityClock()
    }
    private fun noteRunActivity(session: String, result: JSONObject, requestedAt: Long) {
        sessionActivities = updateSessionActivity(sessionActivities, session, runActivityEvidence(result, requestedAt))
        activityNow = activityClock()
    }

    var selectedId by mutableStateOf(prefs.getString("session", null))
        private set

    var title by mutableStateOf(agentName)
        private set
    var titleModel by mutableStateOf<JSONObject?>(null)
        private set
    var titleModelBusy by mutableStateOf(false)
        private set
    var titleModelMessage by mutableStateOf<String?>(null)
        private set
    var titleModelChoices by mutableStateOf<List<String>>(emptyList())
        private set
    var titleModelsLoading by mutableStateOf(false)
        private set
    var titleModelsMessage by mutableStateOf<String?>(null)
        private set
    private var titleModelsRequest = 0

    var messages by mutableStateOf<List<HermesMessage>>(emptyList())
        private set

    var events by mutableStateOf<List<HermesEvent>>(emptyList())
        private set

    // 本 run 累计收到的工具/进度事件总数（events 列表只保留最近若干条，计数不能直接用 events.size）
    var eventCount by mutableStateOf(0)
        private set
    private var externalActivity by mutableStateOf<JSONObject?>(null)
    private var externalVerifiedAt by mutableLongStateOf(0L)
    private var externalHistoryRevision: String? = null
    private var sessionControl by mutableStateOf<JSONObject?>(null)
    var controlMessage by mutableStateOf<String?>(null)
        private set
    private var runVerifiedAt by mutableLongStateOf(0L)
    private var runTurnId: String? = null
    val runStateVerified get() = runId != null && activityNow - runVerifiedAt < 30_000L
    val canTakeover get() = agent == "codex" && selectedId != null && capabilities.optBoolean("session_takeover") &&
        runId == null && !submitting && !hasPendingSubmission && (externalRunning || sessionControl?.optString("state") in setOf("busy", "writer_held") ||
            error.orEmpty().let { "写入权限" in it || "所有者" in it || "接管" in it })
    val writeAccessMessage get() = sessionControl?.takeIf { it.optString("state") in setOf("busy", "writer_held") }?.optString("message")
    private val observation = ForegroundObservation(::watch, ::pauseWatching)
    private var streamConnected = false
    private var observationEpoch = 0L
    private var statusWake: Channel<Unit>? = null
    fun setObserving(value: Boolean) { observation.setActive(value) }
    private fun pauseWatching() {
        observationEpoch++
        watching?.cancel(); watching = null
        streamJob?.cancel(); streamJob = null
        streamConnected = false; statusWake = null
        timingSave?.cancel(); timingSave = null
        saveRuns()
    }
    val externalRunning get() = runId == null && externalActivityRunning(externalActivity, selectedId, activityNow, externalVerifiedAt)
    val hasExecution get() = (runStateVerified && runSession == selectedId) || externalRunning
    val executionKey get() = runId ?: externalActivity?.optString("activity_id")?.takeIf { externalRunning }?.let { "external-$it" }
    val executionTiming get() = if (runId != null) currentRunTiming else externalActivityTiming(externalActivity)
    val executionEvents get() = if (runId != null) events else externalActivityEvents(externalActivity)
    val executionEventCount get() = if (runId != null) eventCount else externalActivity?.optInt("event_count") ?: 0
    val executionState get() = if (runId != null) state else externalActivityLabel(externalActivity)
    val executionCompacting get() = executionState == "正在压缩上下文"

    fun pollExternalActivity() = viewModelScope.launch {
        if (capabilities.optJSONObject("chuckie_features")?.optBoolean("external_session_activity") != true) return@launch
        val id = selectedId ?: return@launch
        if (runId != null || submitting) { externalActivity = null; return@launch }
        val connection = api
        val requestedAt = activityClock()
        activityNow = requestedAt
        try {
            // An IIS/bridge restart can drop the local run subscription while its
            // desktop transport still executes a turn originally submitted here.
            attachServerActiveRun(id)
            if (selectedId != id || api !== connection || runId != null) return@launch
            val result = withTimeout(8_000) { connection.json("$root/sessions/${q(id)}/activity") }
            if (selectedId != id || api !== connection || runId != null) return@launch
            if (!result.optBoolean("available")) return@launch
            externalActivity = result; externalVerifiedAt = requestedAt
            val compactedAt = parseMessageTimestamp(result.opt("compacted_at"))
            val compactedId = result.optString("compaction_id").takeIf { it.isNotBlank() && it != "null" }
            if (compactedAt != null && compactedId != null) recordExternalCompaction(id, compactedId, compactedAt)
            noteRunActivity(id, obj("status" to if (result.optBoolean("running")) "started" else "completed"), requestedAt)
            val revision = "$id:${result.optString("activity_id")}:${result.optLong("revision")}:${result.optBoolean("running")}:${result.optString("kind")}:${result.opt("compacted_at")}"
            if (revision != externalHistoryRevision && historyJob?.isActive != true) {
                loadHistory(id)
                if (selectedId == id && api === connection) externalHistoryRevision = revision
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep only the last verified observation; it expires in 30 seconds. */ }
    }

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
    private var filesReadJob: Job? = null
    private var filesRefreshPending = false
    private var filesReadEpoch = 0L

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
    var sessionEffort by mutableStateOf("")
        private set
    var sessionTier by mutableStateOf("")
        private set
    var modelSaving by mutableStateOf(false)
        private set

    var hasMore by mutableStateOf(false)
        private set

    private var watching: Job? = null
    private var historyJob: Job? = null
    private var streamJob: Job? = null
    private var seq = -1L
    private data class LocalSubmission(val pending: PendingAgentSubmission, val existing: Set<Long>, val after: Long, val files: List<JSONObject>)
    private val localSubmissions = mutableMapOf<String, LocalSubmission>()
    // Keep an explicit user boundary until the run ends, even after history acknowledges
    // and removes the optimistic submission. This is not inferred from the latest row.
    private val runNarrationUsers = mutableMapOf<String, HermesMessage>()
    private var nextCursor: String? = null
    var accounts by mutableStateOf(JSONObject())
        private set
    var workspaces by mutableStateOf(JSONObject())
        private set
    var loginInfo by mutableStateOf<JSONObject?>(null)
        private set
    var loginVisible by mutableStateOf(false)
        private set
    var loginStarting by mutableStateOf(false)
        private set
    var loginError by mutableStateOf<String?>(null)
        private set
    private var loginJob: Job? = null
    var usage by mutableStateOf<JSONObject?>(null)
        private set
    var switchingAccount by mutableStateOf(false)
        private set
    var workspaceUsages by mutableStateOf<Map<String, JSONObject>>(emptyMap())
        private set
    var workspaceUsageLoading by mutableStateOf<Set<String>>(emptySet())
        private set
    private var workspaceReadEpoch = 0L
    var projects by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var projectsLoading by mutableStateOf(false)
        private set
    var contextInfo by mutableStateOf<JSONObject?>(null)
        private set
    private val contextCache = mutableMapOf<String, JSONObject>()
    var asyncQuestion by mutableStateOf<JSONObject?>(null)
        private set
    // 每会话最近一次成功加载的消息列表（内存级，进程内有效），用于 select() 时先回放再刷新。
    private val cachedHistory = mutableMapOf<String, List<HermesMessage>>()
    private var compactionNotices = runCatching {
        org.json.JSONArray(prefs.getString("compactionNotices", "[]")).objects().mapNotNull { AgentCompactionNotice.restore(it) }
    }.getOrDefault(emptyList())
    private suspend fun recordCompactionCompletion(run: String, session: String, result: JSONObject) {
        if (!successfulCompaction(run, result) || compactionNotices.any { it.run == run }) return
        val time = parseMessageTimestamp(result.opt("completed_at")) ?: parseMessageTimestamp(result.opt("finished_at")) ?: System.currentTimeMillis()
        val started = runTimings[run]?.startedAt
        val connection = api
        val observed = if (agent == "codex") try {
            withTimeout(4_000) { connection.json("$root/sessions/${q(session)}/activity") }
        } catch (e: CancellationException) { if (!currentCoroutineContext().isActive) throw e else null }
        catch (_: Exception) { null } else null
        if (api !== connection) return
        val nativeTime = parseMessageTimestamp(observed?.opt("compacted_at"))
        val nativeId = observed?.optString("compaction_id")?.takeIf { it.isNotBlank() && it != "null" &&
            started != null && nativeTime != null && nativeTime >= started && nativeTime <= time + 5_000 }
        val history = if (selectedId == session) messages else cachedHistory[session].orEmpty()
        compactionNotices = coalesceCompactionNotices(compactionNotices + AgentCompactionNotice(run, session,
            if (nativeId != null) nativeTime!! else time, history.lastOrNull { it.serverId > 0 }?.serverId ?: 0, nativeId, started))
        saveCompactionNotices()
        val merged = mergeCompactionNotices(history, compactionNotices.filter { it.session == session })
        cachedHistory[session] = merged
        if (selectedId == session) messages = merged
    }
    private fun saveCompactionNotices() {
        compactionNotices = coalesceCompactionNotices(compactionNotices)
        prefs.edit().putString("compactionNotices", org.json.JSONArray(compactionNotices.map { it.json() }).toString()).apply()
    }
    private fun recordExternalCompaction(session: String, compactedId: String, time: Long) {
        val key = "external-$compactedId"
        if (compactionNotices.any { it.session == session && (it.run == key || it.compactionId == compactedId) }) return
        val history = if (selectedId == session) messages else cachedHistory[session].orEmpty()
        val anchor = history.lastOrNull { it.serverId > 0 && it.timestamp?.let { at -> at <= time } == true }?.serverId ?: 0
        val known = compactionNotices.map { note -> note.copy(startedAt = note.startedAt ?: runTimings[note.run]?.startedAt) }
        compactionNotices = coalesceCompactionNotices(known + AgentCompactionNotice(key, session, time, anchor, compactedId))
        saveCompactionNotices()
        val merged = mergeCompactionNotices(history, compactionNotices.filter { it.session == session })
        cachedHistory[session] = merged
        if (selectedId == session) messages = merged
    }
    // 切走时挂到后台的 run（sessionId -> runId）。服务端继续执行，切回对应会话时恢复跟踪。
    private val backgroundRuns = mutableStateMapOf<String, String>().apply { putAll(runCatching {
        JSONObject(prefs.getString("trackedRuns", "{}").orEmpty()).let { rows -> rows.keys().asSequence().associateWith { rows.getString(it) }.toMutableMap() }
    }.getOrDefault(mutableMapOf())) }
    private val runTimings = mutableStateMapOf<String, AgentRunTiming>().apply { putAll(runCatching {
        JSONObject(prefs.getString("runTimings", "{}").orEmpty()).let { rows ->
            rows.keys().asSequence().associateWith { AgentRunTiming.restore(rows.getJSONObject(it)) }
        }
    }.getOrDefault(emptyMap())) }
    val currentRunTiming get() = runId?.let { runTimings[it] } ?: AgentRunTiming()
    private var timingSave: Job? = null
    private fun saveRunTimings() {
        val tracked = backgroundRuns.values.toSet() + listOfNotNull(runId)
        val rows = JSONObject()
        runTimings.filterKeys { it in tracked }.forEach { (id, timing) -> rows.put(id, timing.json()) }
        prefs.edit().putString("runTimings", rows.toString()).apply()
    }
    private fun startRunTiming(id: String, result: JSONObject, fallback: Long? = null) {
        val old = runTimings[id] ?: AgentRunTiming()
        val started = parseMessageTimestamp(result.opt("started_at")) ?: parseMessageTimestamp(result.opt("created_at"))
        runTimings[id] = old.copy(startedAt = started ?: old.startedAt ?: fallback)
    }
    private fun recordRunResponse(id: String, event: JSONObject) {
        val old = runTimings[id] ?: AgentRunTiming()
        val updated = old.event(event, System.currentTimeMillis())
        if (old == updated) return
        runTimings[id] = updated
        // Coalesce token bursts into one preference write per second.
        if (timingSave?.isActive != true) timingSave = viewModelScope.launch { delay(1000); saveRunTimings() }
    }
    init {
        if (runId != null && runSession != null) backgroundRuns[runSession!!] = runId!!
        runId = selectedId?.let { backgroundRuns.remove(it) }
        runSession = selectedId.takeIf { runId != null }
    }
    private fun saveRuns() {
        val tracked = backgroundRuns.toMutableMap()
        if (runId != null && runSession != null) tracked[runSession!!] = runId!!
        prefs.edit().putString("trackedRuns", JSONObject(tracked as Map<*, *>).toString()).apply()
        saveRunTimings()
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
    private var pendingItemId: String? = null
    private var pendingPhase = ""
    val visiblePendingText get() = visiblePendingAssistant(messages, pendingItemId, pendingText)
    private fun syncPendingCanonical() {
        messages = reconcilePendingAssistant(messages, pendingItemId, pendingText)
    }
    private var steering = runCatching {
        org.json.JSONArray(prefs.getString("steeringMessages", "[]")).objects().map { row ->
            val ids = row.array("existingIds")
            SteeringMessage(row.getString("key"), row.getString("session"), row.getString("text"),
                (0 until ids.length()).map { ids.getLong(it) }.toSet(), row.optLong("anchor"), row.optString("delivery", "发送状态待核对"), parseMessageTimestamp(row.opt("timestamp")), row.array("attachments").objects())
        }
    }.getOrDefault(emptyList())
    private var narrations = runCatching {
        restoreAssistantNarrations(org.json.JSONArray(prefs.getString("assistantNarrations", "[]")))
    }.getOrDefault(emptyList())
    private fun saveNarrations() {
        prefs.edit().putString("assistantNarrations", org.json.JSONArray(narrations.map {
            obj("key" to it.key, "session" to it.session, "text" to it.text, "anchor" to it.anchor,
                "userText" to it.userText, "timestamp" to it.timestamp, "userTimestamp" to it.userTimestamp,
                "sequence" to it.sequence, "positionVersion" to 1, "run" to it.run, "messageId" to it.messageId, "streamed" to it.streamed, "userKey" to it.userKey)
        }).toString()).apply()
    }
    private fun showToolNarration(run: String, tool: String, preview: String, timestamp: Any?, sequence: Long?) {
        val text = terminalNarration(tool, preview) ?: return
        showAssistantNarration(run, text, timestamp, sequence)
    }
    val narrationTexts: List<String> get() = narrations.filter { it.session == selectedId }.map { it.text }
    private fun showAssistantNarration(run: String, text: String, timestamp: Any?, sequence: Long?, messageId: String? = null, streamed: Boolean = false) {
        val session = runSession ?: return
        if (selectedId != session || runId != run) return
        val eventTime = parseMessageTimestamp(timestamp) ?: return
        val identity = messageId?.let { "item-$it" } ?: sequence?.toString() ?: UUID.nameUUIDFromBytes("$eventTime\n$text".toByteArray(Charsets.UTF_8)).toString()
        val key = "narration-$run-$identity"
        val boundUser = runNarrationUsers[run]
        val userKey = boundUser?.localKey ?: localSubmissions[session]?.pending?.key
        var note = assistantNarrationEvent(key, session, text, eventTime, messages, sequence, userKey)?.copy(run = run, messageId = messageId, streamed = streamed) ?: return
        if (boundUser != null) note = note.copy(anchor = boundUser.serverId, userText = boundUser.text, userTimestamp = boundUser.timestamp)
        narrations = upsertAssistantNarration(narrations, note, messages); saveNarrations()
        messages = mergeAssistantNarrations(messages, liveNarrations(session))
        cachedHistory[session] = messages
        if (needsMediaCatalogRefresh(text, files)) refreshFiles()
    }
    private fun flushPendingNarration(run: String, sequence: Long?, text: String = pendingText, eventTime: Any? = pendingTextTimestamp,
        item: String? = pendingItemId) {
        val raw = pendingText
        pendingText = ""; pendingTextTimestamp = null
        flushedStreamPrefix += raw
        if (text.isNotBlank()) showAssistantNarration(run, text, eventTime, sequence, item, streamed = true)
    }
    private fun beginStreamItem(run: String, event: JSONObject, sequence: Long?) {
        val item = event.optString("item_id").takeIf { it.isNotBlank() && it != "null" } ?: return
        if (pendingItemId != item) {
            if (pendingText.isNotBlank() && pendingPhase != "final_answer") flushPendingNarration(run, sequence)
            pendingItemId = item; pendingPhase = ""
        }
        event.optString("phase").takeIf { it.isNotBlank() && it != "null" }?.let { pendingPhase = it }
    }
    /** 旁白气泡 24h 过期：过期后不再插入聊天列表，只在进度区可回看。 */
    private fun liveNarrations(id: String) = narrations.filter {
        it.session == id && System.currentTimeMillis() - it.timestamp < 24 * 60 * 60 * 1000L
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
    private fun clearRunTracking(session: String, id: String) {
        if (backgroundRuns[session] == id) backgroundRuns.remove(session)
        if (runSession == session && runId == id) {
            pauseWatching(); runId = null; runSession = null; approval = null; runVerifiedAt = 0; runTurnId = null
            pendingText = ""; flushedStreamPrefix = ""; pendingTextTimestamp = null; pendingItemId = null; pendingPhase = ""
            events = emptyList(); eventCount = 0
            prefs.edit().remove("run").remove("runSession").apply()
        }
        runNarrationUsers.remove(id); saveRuns()
    }
    fun takeover() = launch {
        val session = selectedId ?: return@launch
        val connection = api
        if (submitting || hasPendingSubmission) return@launch
        val expected = if (runId != null) runTurnId.orEmpty()
            else externalActivity?.optString("activity_id")?.takeIf { it.isNotBlank() } ?: sessionControl?.optString("activity_id").orEmpty()
        setSubmitting(session, true); setError(session, null); controlMessage = null
        try {
            val result = connection.json("$root/sessions/${q(session)}/takeover", obj("expected_activity_id" to expected))
            if (api !== connection || selectedId != session) return@launch
            if (!result.optBoolean("ready")) throw java.io.IOException("尚未确认会话可写入，请刷新后重试")
            runId?.let { clearRunTracking(session, it) }
            externalActivity = null; externalHistoryRevision = null
            state = "就绪"; controlMessage = result.optString("message")
            loadHistory(session); loadSelection(session)
        } finally { setSubmitting(session, false) }
    }
    fun pollContext() = viewModelScope.launch {
        val id = selectedId ?: return@launch
        val connection = api
        if (contextInfo == null) contextInfo = obj("available" to false, "message" to "正在读取")
        try {
            val result = connection.json("$root/sessions/${q(id)}/context")
            if (selectedId != id || api !== connection) return@launch
            contextInfo = result.optJSONObject("context") ?: result
            if (contextInfo?.optBoolean("available") == true) contextCache[id] = contextInfo!!
            if (agent == "codex") asyncQuestion = visibleQuestion(result.optJSONObject("question"), id)
            result.optJSONObject("control")?.let { sessionControl = it }
            result.optString("title").takeIf { it.isNotBlank() && it != "null" }?.let { title = it }
        } catch (e: CancellationException) { throw e }
        catch (error: Exception) {
            if (selectedId == id && api === connection && contextInfo?.optBoolean("available") != true)
                contextInfo = obj("available" to false, "message" to "读取失败，请刷新重试")
        }
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
            val started = System.currentTimeMillis()
            val request = api.request("$root/sessions/${q(id)}/compact", obj()).newBuilder().header("Idempotency-Key", UUID.randomUUID().toString()).build()
            val result = api.json(request)
            startRunTiming(result.getString("run_id"), result, started)
            noteRunActivity(id, obj("status" to result.optString("status", "started")), activityClock())
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
        filesReadEpoch++; filesReadJob?.cancel(); filesReadJob = null; filesRefreshPending = false
        loginJob?.cancel(); loginJob = null; loginInfo = null; loginVisible = false; loginStarting = false; loginError = null
        workspaceReadEpoch++; workspaceUsages = emptyMap(); workspaceUsageLoading = emptySet()
        api = value.withReadPolicy(noRetry = true, onReadSuccess = {
            if (api.base == value.base) {
                if (error == readConnectionError) error = null
                readConnectionError = null
            }
        })
        sessionActivities = emptyMap()
        contextCache.clear()
        externalActivity = null; externalHistoryRevision = null
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

    private suspend fun refreshSessions(offset: Int = 0) = sessionRefreshMutex.withLock {
        val connection = api
        val requestedAt = activityClock()
        loading = true
        try {
            val result = connection.json(if (agent == "codex") "$root/sessions" +
                (if (offset > 0 && nextCursor != null) "?cursor=${q(nextCursor!!)}" else "")
                else "$root/sessions?offset=$offset")
            if (api !== connection) return@withLock
            val rows = result.array("data").objects()
            noteSessionActivities(rows, requestedAt)
            sessions = mergeSessionPage(
                if (offset == 0) emptyList() else sessions,
                rows,
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
        return sessionActivityLabel(sessionActivities[id], activityNow, hasPendingFor(id))
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
        noteRunActivity(session, result, activityClock())
        when (result.optString("status")) {
            "started", "submitting" -> {
                val id = result.getString("run_id")
                startRunTiming(id, result, pending.timestamp)
                markSubmissionAccepted(session, pending.key)
                removePending(session)
                if (selectedId == session) {
                    runId = id; runSession = session
                    seq = -1; events = emptyList(); eventCount = 0; approval = null; pendingText = ""; state = "执行中"
                    runVerifiedAt = activityClock(); activityNow = runVerifiedAt
                    prefs.edit().putString("run", id).putString("runSession", session).putString("runMessageKey", pending.key).apply()
                    watch()
                } else backgroundRuns[session] = id
                saveRuns()
            }
            "completed" -> { removePending(session); if (selectedId == session) loadHistory(session) }
            "failed", "interrupted" -> {
                removePending(session)
                localSubmissions.remove(session)
                if (selectedId == session) { messages = messages.filterNot { it.localKey == pending.key }; cachedHistory[session] = messages }
                if (conversationUi.entry(session).draft.isBlank() && pending.questionId == null) setDraft(session, pending.input)
            }
            "acceptance_unknown" -> {
                setError(session, "上次发送结果尚未确认，请核对会话历史；不要重复发送相同消息")
                if (selectedId == session) loadHistory(session)
            }
        }
    }
    fun pollSessionStates() = viewModelScope.launch {
        activityNow = activityClock()
        if (!sessionRefreshMutex.tryLock()) return@launch
        try {
                val connection = api
                val requestedAt = activityClock()
                val latest = mergeSessionPage(emptyList(), connection.json("$root/sessions").array("data").objects()) { it.optString("id") }
                if (api !== connection) return@launch
                noteSessionActivities(latest, requestedAt)
                val indexed = latest.associateBy { it.optString("id") }
                val known = sessions.map { it.optString("id") }.toSet()
                sessions = latest.filter { it.optString("id") !in known } + sessions.map { indexed[it.optString("id")] ?: it }
            if (agent == "codex") {
                reconcilePendingNow()
            }
            for ((session, id) in backgroundRuns.toMap()) {
                try {
                    val runRequestedAt = activityClock()
                    val result = connection.json("$root/runs/$id")
                    if (api !== connection) return@launch
                    if (backgroundRuns[session] != id) continue
                    noteRunActivity(session, result, runRequestedAt)
                    if (result.optString("status") in setOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown") && backgroundRuns[session] == id) {
                        recordCompactionCompletion(id, session, result)
                        runNarrationUsers.remove(id)
                        backgroundRuns.remove(session); saveRuns()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: ApiRequestFailure) {
                    if (e.status == 404 && backgroundRuns[session] == id && api === connection) {
                        runNarrationUsers.remove(id)
                        noteRunActivity(session, obj("status" to "missing"), activityClock())
                        backgroundRuns.remove(session); saveRuns()
                    }
                } catch (_: Exception) { }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
        finally { sessionRefreshMutex.unlock() }
    }

    private suspend fun fetchAccountsNow() {
        val connection = api
        val loadedAccounts = connection.json("$root/accounts")
        val loadedWorkspaces = connection.json("$root/workspaces")
        if (api !== connection) return
        if (workspaces.optString("current") != loadedWorkspaces.optString("current")) {
            workspaceReadEpoch++; workspaceUsages = emptyMap(); workspaceUsageLoading = emptySet()
        }
        accounts = loadedAccounts; workspaces = loadedWorkspaces
        workspaceUsages = workspaceUsages.filterKeys { id -> workspaces.array("data").objects().any { it.optString("id") == id } }
        fetchWorkspaceUsages()
    }
    fun fetchWorkspaceUsages(force: Boolean = false) {
        if (agent != "codex") return
        workspaces.array("data").objects().forEach { row ->
            val id = row.optString("id")
            if (id.isBlank() || id in workspaceUsageLoading) return@forEach
            workspaceUsageLoading = workspaceUsageLoading + id
            val connection = api
            val epoch = workspaceReadEpoch
            val expectedWorkspace = row.optString("workspace_id")
            viewModelScope.launch {
                try {
                    val result = connection.json("$root/workspaces/${q(id)}/usage" + if (force) "?refresh=true" else "")
                    if (api === connection && epoch == workspaceReadEpoch && result.optString("workspace_id") == id) {
                        val receivedWorkspace = result.optString("chatgpt_account_id")
                        workspaceUsages = workspaceUsages + (id to if (result.optBoolean("available") && receivedWorkspace.isNotBlank() && receivedWorkspace != expectedWorkspace)
                            obj("available" to false, "error" to "返回的工作空间身份不匹配，请刷新") else result)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (api === connection && epoch == workspaceReadEpoch) workspaceUsages = workspaceUsages + (id to obj("available" to false, "error" to "暂时无法读取用量，请刷新重试")) }
                finally { if (api === connection && epoch == workspaceReadEpoch) workspaceUsageLoading = workspaceUsageLoading - id }
            }
        }
    }
    fun fetchAccounts() = launch { error = null; fetchAccountsNow() }

    private suspend fun resetAccountView() {
        historyJob?.cancel()
        selectedId = null
        contextInfo = null; asyncQuestion = null
        sessionProvider = ""; sessionModel = ""; sessionEffort = ""; sessionTier = ""
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
            sessionActivities = emptyMap()
            backgroundRuns.clear(); saveRuns(); pendingSubmissions = emptyMap(); savePending(); conversationUi = ConversationUiState(); draftFiles = emptyMap(); localSubmissions.clear()
            runNarrationUsers.clear()
            workspaceReadEpoch++; workspaceUsages = emptyMap(); workspaceUsageLoading = emptySet()
            prefs.edit().remove("run").remove("runSession").remove("pendingKey").remove("pendingInput").remove("pendingSession").apply()
            resetAccountView()
            fetchUsage()
            onDone()
        } finally { switchingAccount = false }
    }
    fun beginAccountLogin(name: String, workspace: Boolean, onStarted: () -> Unit) {
        loginVisible = true
        if (loginJob?.isActive == true) return
        loginStarting = true; loginError = null; loginInfo = null
        val connection = api
        loginJob = viewModelScope.launch {
            try {
                withTimeout(15 * 60 * 1000L) {
                    val info = connection.json(if (workspace) "$root/workspaces" else "$root/accounts/login", obj("name" to name))
                    if (api !== connection) return@withTimeout
                    loginInfo = info; loginStarting = false; onStarted()
                    while (loginInfo?.optBoolean("completed") != true && api === connection) {
                        delay(2000)
                        val status = connection.json("$root/accounts/login-status")
                        if (api === connection) loginInfo = status
                    }
                    if (api !== connection) return@withTimeout
                    if (loginInfo?.optBoolean("success") == true) fetchAccountsNow()
                    else loginError = loginInfo?.optString("error").orEmpty().ifBlank { "登录未完成，请重新尝试" }
                }
            } catch (_: TimeoutCancellationException) {
                if (api === connection) loginError = "登录等待超时，请重新打开登录页面"
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (api === connection) loginError = connectionFailureMessage(e) }
            finally { if (api === connection) loginStarting = false }
        }
    }
    fun dismissLogin() { loginVisible = false }
    fun showLogin() { loginVisible = true }
    fun fetchUsage() = viewModelScope.launch {
        val connection = api
        try { val result = connection.json("$root/usage"); if (api === connection) usage = result }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (api === connection) usage = null }
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
            pendingText = ""; flushedStreamPrefix = ""; pendingTextTimestamp = null; pendingItemId = null; pendingPhase = ""
            events = emptyList(); eventCount = 0
            watching?.cancel(); streamJob?.cancel()
        }
        if (selectedId != id) {
            externalActivity = null; externalHistoryRevision = null; sessionControl = null; controlMessage = null; runVerifiedAt = 0
            runSession = null
            seq = -1; events = emptyList(); eventCount = 0
            pendingText = ""; pendingTextTimestamp = null; flushedStreamPrefix = ""; pendingItemId = null; pendingPhase = ""
            state = "就绪"; readConnectionError = null; files = emptyList()
            prefs.edit().remove("run").remove("runSession").apply()
        }
        selectedId = id
        title = session.optString("title").ifBlank { "未命名会话" }
        prefs.edit().putString("session", selectedId).apply()
        historyJob?.cancel()
        sessionProvider = ""; sessionModel = ""; sessionEffort = ""; sessionTier = ""
        contextInfo = contextCache[id]; asyncQuestion = null
        // 先回放上一次该会话的消息（如有缓存），网络刷新到位后替换 —— 消除「返回再进白屏等待」。
        cachedHistory[selectedId]?.let { cached ->
            messages = cached
            // 回放缓存后请求滚到底，否则打开会话停在缓存顶部等网络刷新
            scrollToLatestRequest++
        } ?: run { messages = emptyList() }
        // 恢复该会话的后台 run 跟踪
        backgroundRuns.remove(id)?.let { resumed ->
            runId = resumed; runSession = id
            prefs.edit().putString("run", resumed).putString("runSession", id).apply()
            watch()
        }
        saveRuns()
        pollContext()
        historyJob = launch { reconcilePendingNow(id); loadHistory(id); loadSelection(id) }
    }

    /** 多端共享：本地没有跟踪时，向服务端查询该会话是否有其它设备发起的活跃 run，有则以观察者身份挂载。 */
    private suspend fun attachServerActiveRun(id: String, allowSubmitting: Boolean = false) {
        if (runId != null || submitting && !allowSubmitting) return
        val connection = api
        try {
            val result = connection.json("$root/sessions/${q(id)}/active-run")
            if (selectedId != id || api !== connection || runId != null) return
            val serverRun = result.optString("run_id").takeIf { it.isNotBlank() && it != "null" } ?: return
            if (result.optString("status") !in setOf("started", "submitting")) return
            startRunTiming(serverRun, result)
            runId = serverRun; runSession = id
            seq = -1; events = emptyList(); eventCount = 0; approval = null; pendingText = ""
            prefs.edit().putString("run", serverRun).putString("runSession", id).apply()
            state = "执行中"
            runVerifiedAt = activityClock(); activityNow = runVerifiedAt
            noteRunActivity(id, result, runVerifiedAt)
            saveRuns(); watch()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { }
    }
    private suspend fun loadSelection(id: String) {
        val connection = api
        val result = connection.json("$root/sessions/$id").optJSONObject("session") ?: return
        if (selectedId != id || api !== connection) return
        result.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
        val selection = readAgentSelection(result)
        sessionProvider = selection.provider; sessionModel = selection.model
        sessionEffort = selection.effort; sessionTier = selection.tier
        runtime = listOf(sessionProvider, sessionModel).filter { it.isNotBlank() }.joinToString(" · ")
        result.optJSONObject("context")?.let { contextInfo = it; if (it.optBoolean("available")) contextCache[id] = it }
        asyncQuestion = visibleQuestion(result.optJSONObject("question"), id)
        sessionControl = result.optJSONObject("control")
        pollContext()
        attachServerActiveRun(id)
    }

    private suspend fun loadHistory(id: String) = historyReadMutex.withLock {
        if (id == selectedId) loadHistoryNow(id)
    }
    private suspend fun loadHistoryNow(id: String) {
        val connection = api
        val response = connection.json("$root/sessions/$id/messages")
        if (id != selectedId || api !== connection) return
        // Keep the visible list intact while acknowledgement/attachment requests suspend.
        // SSE can add narration during those requests; merge the latest local state only
        // at publication, rather than exposing a bare/stale server snapshot to Compose.
        var history =
            retainUserMessageMetadata(
                response.array("data").objects().mapNotNull { row -> agentHistoryMessage(agent, row) }, messages,
            )
        val sessionSteering = steering.filter { it.session == id }
        val reconciliation = reconcileSteeringMessages(history, sessionSteering)
        for ((sent, confirmed) in reconciliation.second) {
            val canonical = confirmed.copy(timestamp = confirmed.timestamp ?: sent.timestamp, localKey = sent.key)
            history = history.map { if (it.serverId == confirmed.serverId) canonical else it }
            narrations = acknowledgeNarrationUser(narrations, id, sent.key, canonical)
            if (sent.attachments.isNotEmpty()) {
                connection.json("$root/sessions/$id/messages/${confirmed.serverId}/attachments",
                    obj("ids" to org.json.JSONArray(sent.attachments.map { it.getString("id") })))
                if (selectedId != id || api !== connection) return
                history = history.map { if (it.serverId == confirmed.serverId) it.copy(attachments = sent.attachments) else it }
            }
        }
        // 放弃的失败记录（>10 分钟）与已确认/滑出窗口的记录一并清除，防止本地气泡永久堆叠在列表尾部
        val abandonedKeys = sessionSteering.filter { isAbandonedSteering(it) }.map { it.key }.toSet()
        if (abandonedKeys.isNotEmpty()) setError(id, "未送达的插话已从本机记录清除；如仍需发送，请重新输入")
        val refreshedKeys = sessionSteering.map { it.key }.toSet()
        steering = steering.filter { it.session != id || it.key !in refreshedKeys } + reconciliation.first.filter { it.key !in abandonedKeys }
        saveSteering()
        localSubmissions[id]?.let { local ->
            val acknowledged = history.firstOrNull {
                it.role == "user" && (if (agent == "codex") it.serverId !in local.existing else it.serverId > local.after) &&
                    it.text.replace("\r\n", "\n").trim() == local.pending.input.replace("\r\n", "\n").trim()
            }
            if (acknowledged != null) {
                val canonical = acknowledged.copy(timestamp = acknowledged.timestamp ?: local.pending.timestamp, localKey = local.pending.key)
                history = history.map { if (it.serverId == acknowledged.serverId) canonical else it }
                if (pendingSubmissions[id]?.key == local.pending.key) removePending(id)
                if (local.files.isNotEmpty()) {
                    connection.json("$root/sessions/$id/messages/${acknowledged.serverId}/attachments",
                        obj("ids" to org.json.JSONArray(local.files.map { it.getString("id") })))
                    if (selectedId != id || api !== connection) return
                    history = history.map { if (it.serverId == acknowledged.serverId) it.copy(attachments = local.files) else it }
                }
                if (localSubmissions[id]?.pending?.key == local.pending.key) localSubmissions.remove(id)
                narrations = acknowledgeNarrationUser(narrations, id, local.pending.key, canonical)
                runNarrationUsers.entries.forEach { entry ->
                    if (entry.value.localKey == local.pending.key) entry.setValue(canonical)
                }
            }
        }
        // Everything below is synchronous: no observer sees the unreconciled snapshot.
        saveNarrations()
        history = mergeSteeringMessages(history, steering.filter { it.session == id })
        localSubmissions[id]?.let { local ->
            history = insertLocalMessage(history, HermesMessage("user", local.pending.input, attachments = local.files,
                localKey = local.pending.key, delivery = if (hasPendingFor(id)) {
                    if (submitting) "正在发送" else "发送状态待核对"
                } else "已送达", timestamp = local.pending.timestamp), local.existing, local.after)
        }
        history = mergeAssistantNarrations(history, liveNarrations(id))
        history = reconcilePendingAssistant(history, pendingItemId, pendingText)
        messages = mergeCompactionNotices(history, compactionNotices.filter { it.session == id })
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
            body.put("auto_title", name.isBlank() || Regex("手机对话\\d{8}_\\d{6}").matches(name))
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
        sessionActivities = sessionActivities - id
        compactionNotices = compactionNotices.filterNot { it.session == id }; saveCompactionNotices()
        narrations = narrations.filterNot { it.session == id }; saveNarrations()
        localSubmissions.remove(id)
        draftFiles = draftFiles - id
        conversationUi = conversationUi.forget(id)
        steering = steering.filterNot { it.session == id }
        saveSteering()
        if (selectedId == id) {
            selectedId = null
            contextInfo = null; asyncQuestion = null
            sessionProvider = ""; sessionModel = ""; sessionEffort = ""; sessionTier = ""
            title = "$agentName 会话"
            messages = emptyList()
            files = emptyList()
            pendingFiles = emptyList()
            draft = ""
            prefs.edit().remove("session").apply()
        }
        onDone()
    }

    internal val messageQueue: DeferredChatQueue by lazy {
        DeferredChatQueue(prefs, viewModelScope, agent, { api }, { selectedId },
            { queueMayDispatch(runId != null || externalRunning, submitting, hasPendingSubmission, false, false) },
            { row, result -> dispatchQueuedMessage(row, result) })
    }
    fun enqueueDraft(): Boolean {
        if (submitting || uploading || selectedId == null) return false
        if (draft.trim().startsWith("/")) { error = "斜杠命令请直接执行，消息队列用于对话内容"; return false }
        val files = pendingFiles.toList()
        if (!messageQueue.enqueue(draft.trim(), files)) return false
        draft = ""; pendingFiles = emptyList()
        return true
    }
    fun pollMessageQueue() = viewModelScope.launch {
        if (agent == "codex" && hasPendingSubmission && messageQueue.visible.any { it.key == pendingSubmissions[selectedId]?.key })
            try { reconcilePendingNow() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        messageQueue.tick()
    }
    private fun dispatchQueuedMessage(row: QueuedChatMessage, response: JSONObject?) {
        if (selectedId != row.session || runId != null || submitting || hasPendingSubmission) return
        val pending = PendingAgentSubmission(row.key, row.text, row.files.map { it.getString("id") }, System.currentTimeMillis())
        if (response == null) {
            pendingSubmissions = pendingSubmissions + (row.session to pending); savePending()
            submit(pending, row.session, row.files, clearDraft = false)
        } else {
            localSubmissions[row.session] = LocalSubmission(pending, messages.map { it.serverId }.toSet(), messages.maxOfOrNull { it.serverId } ?: 0, row.files)
            messages = messages + HermesMessage("user", row.text, attachments = row.files, localKey = row.key, delivery = "已送达", timestamp = pending.timestamp)
            scrollToLatestRequest++
            val run = response.getString("run_id")
            runId = run; runSession = row.session; startRunTiming(run, response, pending.timestamp)
            runNarrationUsers[run] = messages.last()
            prefs.edit().putString("run", run).putString("runSession", row.session).putString("runMessageKey", row.key).apply()
            seq = -1; events = emptyList(); eventCount = 0; pendingText = ""; approval = null; state = "执行中"
            runVerifiedAt = activityClock(); activityNow = runVerifiedAt; saveRuns(); watch()
            launch { loadHistory(row.session) }
        }
    }

    fun send(): Boolean = sendInput(draft.trim().ifBlank { if (pendingFiles.isNotEmpty()) "请查看附件" else "" })

    private fun sendInput(input: String, questionId: String? = null): Boolean {
        val session = selectedId ?: return false
        if (hasPendingSubmission) { error = "请先核对上一次提交结果，避免重复创建任务"; return false }
        if (input.isBlank() || submitting) return false
        if (externalRunning && !(agent == "codex" && questionId != null)) {
            if (agent != "codex") { error = "其他端正在执行，本次消息未写入；可等待结束或中断并接管。文字和附件已保留。"; return false }
            val connection = api
            val originalDraft = draft
            val originalFiles = pendingFiles.map { it.optString("id") }
            setSubmitting(session, true)
            viewModelScope.launch {
                var dispatched = false
                try {
                    withTimeout(8_000) { attachServerActiveRun(session, allowSubmitting = true) }
                    if (selectedId != session || api !== connection) return@launch
                    if (runId == null) {
                        val current = withTimeout(8_000) { connection.json("$root/sessions/${q(session)}/activity") }
                        if (selectedId != session || api !== connection) return@launch
                        if (!current.optBoolean("available")) { setError(session, "暂时无法核对任务归属，文字和附件已保留"); return@launch }
                        externalActivity = current; externalVerifiedAt = activityClock(); activityNow = externalVerifiedAt
                        if (current.optBoolean("running")) { setError(session, "其他端正在执行，本次消息未写入；文字和附件已保留。"); return@launch }
                    }
                    // Dispatch this explicit click once, after verifying its original draft/attachments.
                    if (draft == originalDraft && pendingFiles.map { it.optString("id") } == originalFiles) {
                        setSubmitting(session, false)
                        dispatched = sendInput(input, questionId)
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { setError(session, "核对任务归属超时，文字和附件已保留，请重新连接")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { setError(session, "无法核对任务归属，文字和附件已保留，请重新连接") }
                finally { if (!dispatched) setSubmitting(session, false) }
            }
            return true
        }
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
            val connection = api
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
                var accepted = false
                try {
                    val live = connection.json("$root/runs/$id")
                    if (live.optString("status") != "started") throw ApiRequestFailure("原任务已结束，本次插话未写入。草稿已保留，请作为新消息发送。", 409, "run_stale", "rejected")
                    val request = connection.request("$root/runs/$id/steer", obj("input" to input, "session_id" to session, "attachment_ids" to org.json.JSONArray(attached.map { it.getString("id") }))).newBuilder().header("Idempotency-Key", steerKey).build()
                    connection.json(request)
                    accepted = true
                    questionAccepted(session); updateSteering(steerKey, "已送达")
                    if (selectedId == session && runId == id && api === connection)
                        messages.firstOrNull { it.role == "user" && it.localKey == steerKey }?.let { runNarrationUsers[id] = it }
                    val ids = attached.map { it.getString("id") }.toSet()
                    draftFiles = draftFiles + (session to draftFiles[session].orEmpty().filterNot { it.optString("id") in ids })
                    if (selectedId == session && api === connection) loadHistory(session)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (accepted) { setError(session, "插话已送达，暂时无法刷新历史。${connectionFailureMessage(e)}"); return@launch }
                    setError(session, if (writeWasRejected(e)) connectionFailureMessage(e)
                        else "插话发送结果尚未确认，请核对会话历史后再决定是否重发。${connectionFailureMessage(e)}")
                    updateSteering(steerKey, steeringFailureDelivery(e))
                    if (writeWasRejected(e)) {
                        steering = steering.filterNot { it.key == steerKey }; saveSteering()
                        if (selectedId == session) { messages = messages.filterNot { it.localKey == steerKey }; cachedHistory[session] = messages }
                    }
                    if (staleRunFailure(e)) clearRunTracking(session, id)
                    if (!isQuestion && conversationUi.entry(session).draft.isBlank()) setDraft(session, input)
                    if (isQuestion) prefs.edit().remove("answeringQuestion:$session").apply()
                    if (selectedId == session && api === connection) try { loadHistory(session); loadSelection(session) }
                    catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
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

    private fun markSubmissionAccepted(session: String, key: String) {
        messageQueue.accepted(key)
        if (selectedId == session) messages = messages.map {
            if (it.role == "user" && it.localKey == key) it.copy(delivery = "已送达") else it
        }
        cachedHistory[session]?.let { rows -> cachedHistory[session] = rows.map {
            if (it.role == "user" && it.localKey == key) it.copy(delivery = "已送达") else it
        } }
    }

    private fun submit(pending: PendingAgentSubmission, session: String, queuedFiles: List<JSONObject>? = null, clearDraft: Boolean = true) {
        val key = pending.key; val input = pending.input
        val provider = sessionProvider; val model = sessionModel
        val attached = queuedFiles ?: pendingFiles.filter { it.optString("id") in pending.attachmentIds }
        setSubmitting(session, true); setError(session, null)
        if (localSubmissions[session]?.pending?.key != key) {
            localSubmissions[session] = LocalSubmission(pending, messages.map { it.serverId }.toSet(), messages.maxOfOrNull { it.serverId } ?: 0, attached)
            if (selectedId == session) {
                messages = messages + HermesMessage("user", input, attachments = attached, localKey = key, delivery = "正在发送", timestamp = pending.timestamp)
                scrollToLatestRequest++
            }
        }
        if (clearDraft && pending.questionId == null) setDraft(session, "")
        launch {
            var accepted = false
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
                when (response.optString("status")) {
                    "acceptance_unknown" -> throw ApiRequestFailure("上次发送结果尚未确认，请核对会话历史；不会重复发送", 409, "delivery_unknown", "unknown")
                    "failed", "interrupted" -> throw ApiRequestFailure("原请求已结束，本次没有再次写入。草稿已保留，请核对后重新发送。", 409, "run_stale", "rejected")
                    "completed" -> { accepted = true; markSubmissionAccepted(session, key); removePending(session); if (selectedId == session) loadHistory(session); return@launch }
                }
                startRunTiming(startedRun, response, pending.timestamp)
                noteRunActivity(session, obj("status" to response.optString("status", "started")), activityClock())
                accepted = true
                markSubmissionAccepted(session, key)
                questionAccepted(session); removePending(session)
                if (selectedId == session) {
                    runId = startedRun; runSession = session
                    messages.firstOrNull { it.role == "user" && it.localKey == key }?.let { runNarrationUsers[startedRun] = it }
                    prefs.edit().putString("runMessageKey", key).putString("run", startedRun).putString("runSession", session).apply()
                    pendingFiles = pendingFiles.filterNot { it.optString("id") in pending.attachmentIds }
                    pendingText = ""; flushedStreamPrefix = ""; pendingTextTimestamp = null; pendingItemId = null; pendingPhase = ""
                    events = emptyList(); eventCount = 0; approval = null; seq = -1; state = "执行中"
                    runVerifiedAt = activityClock(); activityNow = runVerifiedAt
                    watch(); loadHistory(session)
                } else backgroundRuns[session] = startedRun
                saveRuns()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (accepted) { setError(session, "消息已送达，暂时无法刷新历史。${connectionFailureMessage(e)}"); return@launch }
                messageQueue.failed(key, writeWasRejected(e))
                if (writeWasRejected(e)) {
                    removePending(session)
                    prefs.edit().remove("answeringQuestion:$session").apply()
                    if (clearDraft && pending.questionId == null && conversationUi.entry(session).draft.isBlank()) setDraft(session, input)
                    localSubmissions.remove(session)
                    if (selectedId == session) {
                        messages = messages.filterNot { it.localKey == key }; cachedHistory[session] = messages
                        pollContext()
                    }
                } else {
                    if (selectedId == session) messages = messages.map { if (it.localKey == key) it.copy(delivery = "发送状态待核对") else it }
                    if (agent == "codex") try { reconcilePendingNow(session) } catch (_: Exception) { }
                }
                setError(session, if (writeWasRejected(e)) connectionFailureMessage(e)
                    else "消息发送结果尚未确认，请核对会话历史；不要重复发送。${connectionFailureMessage(e)}")
            } finally { setSubmitting(session, false) }
        }
    }

    private fun watch() {
        val epoch = ++observationEpoch
        watching?.cancel()
        streamJob?.cancel()
        streamConnected = false
        if (!observation.active || !this::api.isInitialized) return
        val id = runId ?: return
        val watchedSession = runSession ?: return
        val connection = api
        val wake = Channel<Unit>(Channel.CONFLATED)
        statusWake = wake
        streamJob =
            viewModelScope.launch {
                var reconnects = 0
                while (isActive && observation.active && runId == id) {
                    val started = activityClock()
                    try {
                        readEvents(connection, id, epoch)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        /* Status polling remains authoritative; reconnect only this GET stream. */
                    }
                    if (activityClock() - started >= 15_000) reconnects = 0
                    reconnects++
                    if (runId == id) delay((1_500L * (1L shl (reconnects - 1).coerceIn(0, 4))).coerceAtMost(30_000))
                }
            }
        watching = launch {
            var failures = 0
            while (isActive && observation.active && runId == id) {
                try {
                    activityNow = activityClock()
                    val requestedAt = activityNow
                    val result = connection.json("$root/runs/$id")
                    currentCoroutineContext().ensureActive()
                    if (runId != id || api !== connection) return@launch
                    failures = 0
                    noteRunActivity(watchedSession, result, requestedAt)
                    runVerifiedAt = requestedAt
                    runTurnId = result.optString("turn_id").takeIf { it.isNotBlank() && it != "null" }
                    startRunTiming(id, result)
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
                        runSession?.let { recordCompactionCompletion(id, it, result) }
                        if (result.optString("status") != "completed")
                            error = result.optString("error").ifBlank { state }
                        if (result.optString("status") != "acceptance_unknown" && pendingItemId == null && result.optString("output").isNotBlank())
                            pendingText = result.optString("output").removePrefix(flushedStreamPrefix)
                        runId = null
                        runVerifiedAt = 0
                        runNarrationUsers.remove(id)
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
                    failures++
                    // run 在服务端已不存在（404 run_not_found）：视为任务已终结，清除跟踪而不是无限重试刷错误。
                    if (e is ApiRequestFailure && e.status == 404) {
                        noteRunActivity(watchedSession, obj("status" to "missing"), activityClock())
                        runId = null; approval = null
                        runNarrationUsers.remove(id)
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
                withTimeoutOrNull(agentStatusPollDelay(streamConnected, failures)) { wake.receive() }
            }
        }
    }

    fun reconnect() {
        error = null
        if (runId != null) watch() else refresh()
    }

    private suspend fun readEvents(connection: NativeApi, id: String, epoch: Long) {
        val request =
            connection.request("$root/runs/$id/events")
                .newBuilder()
                .apply { if (seq >= 0) header("Last-Event-ID", seq.toString()) }
                .build()
        try { connection.streamingResponse(request) { response ->
            if (!response.isSuccessful) return@streamingResponse
            if (runId != id || api !== connection || !observation.active || epoch != observationEpoch) return@streamingResponse
            streamConnected = true
            withContext(Dispatchers.IO) {
                response.body!!.charStream().buffered().use { reader ->
                    val parser = HermesSseParser()
                    while (currentCoroutineContext().isActive) {
                        val line = reader.readLine() ?: break
                        parser.line(line)?.let { json ->
                            val event = JSONObject(json)
                            withContext(Dispatchers.Main) {
                                if (runId != id || api !== connection || !observation.active || epoch != observationEpoch) return@withContext
                                val n = event.optLong("seq", -1)
                                if (n >= 0 && n <= seq) return@withContext
                                if (n >= 0) seq = n
                                recordRunResponse(id, event)
                                if (event.optString("type", event.optString("event")) in setOf("approval.request", "run.completed", "run.failed", "run.cancelled", "run.interrupted")) statusWake?.trySend(Unit)
                                when (event.optString("type", event.optString("event"))) {
                                    "message.started" -> beginStreamItem(id, event, n.takeIf { it >= 0 })
                                    "message.delta" -> {
                                        beginStreamItem(id, event, n.takeIf { it >= 0 })
                                        if (pendingTextTimestamp == null) pendingTextTimestamp = parseMessageTimestamp(event.opt("timestamp")) ?: System.currentTimeMillis()
                                        pendingText += event.optString("delta")
                                        syncPendingCanonical()
                                    }
                                    "message.snapshot", "message.completed" -> {
                                        beginStreamItem(id, event, n.takeIf { it >= 0 })
                                        if (pendingTextTimestamp == null) pendingTextTimestamp = parseMessageTimestamp(event.opt("timestamp")) ?: System.currentTimeMillis()
                                        pendingText = event.optString("text")
                                        syncPendingCanonical()
                                        if (needsMediaCatalogRefresh(pendingText, files)) refreshFiles()
                                        if (event.optString("type", event.optString("event")) == "message.completed" && pendingPhase == "commentary")
                                            flushPendingNarration(id, n.takeIf { it >= 0 })
                                    }
                                    "approval.request" -> approval = event
                                    "tool.started" -> {
                                        // Legacy Hermes/Codex streams have no item ID: a tool starts only
                                        // after the preceding assistant segment, so close that segment now.
                                        if (pendingText.isNotBlank() && pendingPhase != "final_answer") flushPendingNarration(id, n.takeIf { it >= 0 })
                                        showToolNarration(id, event.optString("tool"), event.optString("preview"), event.opt("timestamp"), n.takeIf { it >= 0 })
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
                                        val text = event.optString("text")
                                        if (text.isNotBlank()) {
                                            if (event.optBoolean("already_streamed") || pendingText.trim() == text.trim())
                                                flushPendingNarration(id, n.takeIf { it >= 0 }, text,
                                                    pendingTextTimestamp ?: event.opt("timestamp"))
                                            else showAssistantNarration(id, text, event.opt("timestamp"), n.takeIf { it >= 0 }, streamed = true)
                                            events = (events + HermesEvent("进度", truncateNarration(text).take(100))).takeLast(30)
                                            eventCount++
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } } finally { if (epoch == observationEpoch) streamConnected = false }
    }

    fun stop() = launch {
        runId?.let { id ->
            val session = runSession ?: return@let
            val result = api.json("$root/runs/$id/stop", obj())
            if (result.optBoolean("stopped") && result.has("stop_requested")) {
                clearRunTracking(session, id); state = "任务已结束"
                if (selectedId == session) { loadHistory(session); loadSelection(session) }
            } else if (runId == id) state = "已请求停止，等待任务结束确认"
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

    fun refreshFiles() {
        val session = selectedId ?: return; val connection = api
        if (filesReadJob?.isActive == true) { filesRefreshPending = true; return }
        val epoch = ++filesReadEpoch
        filesReadJob = launch {
            try {
                do {
                    filesRefreshPending = false
                    val result = connection.json("$root/sessions/$session/files").array("data").objects()
                    if (selectedId != session || api !== connection) return@launch
                    files = result
                } while (filesRefreshPending)
            } finally { if (filesReadEpoch == epoch) filesReadJob = null }
        }
    }

    fun removePendingFile(id: String) {
        pendingFiles = pendingFiles.filter { it.optString("id") != id }
    }

    fun upload(uri: android.net.Uri, image: Boolean) {
        val session = selectedId ?: return; val connection = api
        if (uploading) return
        if (pendingFiles.size >= 8) { setError(session, "每条消息最多 8 个附件"); return }
        uploading = true
        launch {
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
                    connection.request("$root/sessions/$session/files")
                        .newBuilder()
                        .post(multipart)
                        .build()
                val result = connection.json(request)
                draftFiles = draftFiles + (session to (draftFiles[session].orEmpty() + result))
            } finally {
                file.temporary.delete()
            }
        } finally {
            uploading = false
        }
        }
    }

    fun fetchProviders() = launch { providers = api.json("$root/providers") }
    fun fetchTitleModel() = launch {
        titleModel = null; titleModelMessage = null; titleModelChoices = emptyList(); titleModelsMessage = null
        try { titleModel = api.json("$root/title-model") }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { titleModelMessage = e.message ?: "配置读取失败，请关闭后重试" }
    }
    fun invalidateTitleModels() {
        titleModelsRequest++; titleModelChoices = emptyList(); titleModelsMessage = null; titleModelsLoading = false
    }
    fun fetchTitleModels(body: JSONObject) = viewModelScope.launch {
        val request = ++titleModelsRequest
        titleModelsLoading = true; titleModelsMessage = null; titleModelChoices = emptyList()
        try {
            val result = api.json("$root/title-model/models", body)
            if (request == titleModelsRequest) {
                val values = result.optJSONArray("models")
                titleModelChoices = if (values == null) emptyList() else (0 until values.length()).map { values.getString(it) }
                titleModelsMessage = "已获取 ${titleModelChoices.size} 个模型，可选择或手动填写"
            }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (request == titleModelsRequest) titleModelsMessage = e.message ?: "获取失败，可手动填写模型名" }
        finally { if (request == titleModelsRequest) titleModelsLoading = false }
    }
    fun saveTitleModel(body: JSONObject, test: Boolean, onSaved: () -> Unit) = launch {
        if (titleModelBusy) return@launch
        titleModelBusy = true; titleModelMessage = null
        try {
            titleModel = api.json("$root/title-model", body)
            onSaved()
            titleModelMessage = if (test) "测试成功：" + api.json("$root/title-model/test", obj()).getString("title") else "已保存"
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { titleModelMessage = e.message ?: "保存或测试失败" }
        finally { titleModelBusy = false }
    }

    fun setModel(
        provider: String,
        model: String,
        global: Boolean,
        onDone: () -> Unit,
        confirm: Boolean = false,
        effort: String = "",
        tier: String = "",
    ) {
        if (modelSaving) return
        val session = selectedId; val connection = api
        modelSaving = true
        launch {
        try {
        val body = obj("provider" to provider, "model" to model)
        body.put("reasoning_effort", effort)
        if (agent == "codex") body.put("service_tier", tier)
        else if (!global) body.put("model_options", obj("reasoning" to hermesReasoning(effort)))
        if (global) {
            body.put("scope", "main")
            body.put("confirm_expensive_model", confirm)
            val result = connection.json("$root/default-model", body)
            if (result.optBoolean("confirm_required")) {
                modelWarning = result.optString("confirm_message")
                return@launch
            }
        } else {
            val id = session ?: throw java.io.IOException("请先选择会话")
            connection.json("$root/sessions/$id/model", body)
            if (selectedId == id && api === connection) {
                runtime = "$provider · $model"
                sessionProvider = provider; sessionModel = model; sessionEffort = effort; sessionTier = tier
            }
        }
        val options = connection.json("$root/model-options")
        if (api !== connection) return@launch
        modelOptions = options
        modelWarning = null
        onDone()
        } finally { modelSaving = false }
        }
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
