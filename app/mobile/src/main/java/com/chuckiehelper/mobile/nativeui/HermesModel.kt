package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.runtime.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
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
    val nativeTurnId: String? = null,
    val editable: Boolean = true,
    val requestKey: String? = null,
    /** clarify / request_user_input cards: [{question, answer, status, choices[]}] for role=system type=interaction. */
    val interactions: List<JSONObject> = emptyList(),
    val messageType: String? = null,
)

data class HermesEvent(val type: String, val text: String, val detail: String = "", val itemId: String? = null)

/** A null part means that only that part needs the legacy endpoint fallback. */
internal data class SessionOverview(
    val detail: JSONObject?, val history: JSONObject?, val context: JSONObject?,
    val activeRun: JSONObject?, val activity: JSONObject?, val files: JSONObject?
) {
    companion object {
        fun read(value: JSONObject) = SessionOverview(
            detail = value.optJSONObject("detail")?.takeIf { it.optJSONObject("session") != null || it.has("id") },
            history = value.optJSONObject("history")?.takeIf { it.optJSONArray("data") != null },
            context = value.optJSONObject("context"),
            activeRun = value.optJSONObject("active_run")?.takeIf { it.has("run_id") },
            activity = value.optJSONObject("activity")?.takeIf { it.has("available") },
            files = value.optJSONObject("files")?.takeIf { it.optJSONArray("data") != null }
        )
    }
}

internal fun currentCodexIdentity(accounts: JSONObject, workspaces: JSONObject): String? =
    (if (workspaces.has("current")) workspaces else accounts).optString("current")
        .takeIf { it.isNotBlank() && it != "null" }

/** Durable run ids, never a persisted provider secret or automatically replayed prompt. */
class HermesModel(application: Application, deviceId: String, val agent: String = "hermes") : AndroidViewModel(application) {
    val agentName = if (agent == "codex") "Codex" else "Hermes"
    private val root = "/api/$agent"
    private val prefs = application.getSharedPreferences("$agent-$deviceId", 0)
    private val appContext = application.applicationContext
    private val watchOwner = "$agent-$deviceId"
    private lateinit var api: NativeApi
    val sessionsListState = LazyListState()

    var sessionSearchQuery by mutableStateOf("")
        private set
    var sessionSearchResults by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var sessionSearchLoading by mutableStateOf(false)
        private set
    var sessionSearchError by mutableStateOf<String?>(null)
        private set
    var sessionSearchHasMore by mutableStateOf(false)
        private set
    private var sessionSearchCursor: String? = null
    private var sessionSearchOffset = 0
    private var sessionSearchEpoch = 0L
    private var sessionSearchJob: Job? = null
    fun searchSessions(query: String) {
        sessionSearchQuery = query.trim()
        sessionSearchEpoch++; sessionSearchJob?.cancel()
        sessionSearchResults = emptyList(); sessionSearchError = null; sessionSearchHasMore = false
        sessionSearchCursor = null; sessionSearchOffset = 0
        sessionSearchLoading = sessionSearchQuery.isNotEmpty()
        if (sessionSearchQuery.isNotEmpty()) readSessionSearch(false)
    }
    fun moreSearchSessions() { if (!sessionSearchLoading && sessionSearchHasMore) readSessionSearch(true) }
    private fun readSessionSearch(append: Boolean) {
        val query = sessionSearchQuery; val epoch = sessionSearchEpoch
        sessionSearchLoading = true; sessionSearchError = null
        sessionSearchJob = viewModelScope.launch {
            try {
                if (!append) delay(350)
                if (!this@HermesModel::api.isInitialized) throw IOException("尚未连接设备")
                val connection = api
                val suffix = if (agent == "codex") sessionSearchCursor?.let { "&cursor=${q(it)}" }.orEmpty()
                    else "&offset=$sessionSearchOffset"
                val result = connection.json("$root/sessions/search?query=${q(query)}$suffix")
                if (epoch != sessionSearchEpoch || api !== connection) return@launch
                val rows = result.array("data").objects()
                noteSessionActivities(rows, activityClock())
                sessionSearchResults = mergeSessionPage(if (append) sessionSearchResults else emptyList(), rows) { it.optString("id") }
                sessionSearchHasMore = result.optBoolean("has_more")
                sessionSearchCursor = result.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
                sessionSearchOffset = result.optInt("offset", sessionSearchOffset) + result.optInt("limit", 50)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (epoch == sessionSearchEpoch) sessionSearchError = e.message ?: "原生搜索失败，请更新该设备服务端后重试" }
            finally { if (epoch == sessionSearchEpoch) sessionSearchLoading = false }
        }
    }
    var sessions by mutableStateOf<List<JSONObject>>(runCatching {
        org.json.JSONArray(prefs.getString("offlineSessions", "[]")).objects()
    }.getOrDefault(emptyList()))
        private set
    var pinningSessions by mutableStateOf<Set<String>>(emptySet())
        private set
    private var nextSessionOffset = 0
    private var messageActionSessions by mutableStateOf<Set<String>>(emptySet())
    val messageActionBusy get() = selectedId in messageActionSessions
    val supportsMessageActions get() = if (agent == "codex") capabilities.optBoolean("message_actions")
        else capabilities.optJSONObject("chuckie_features")?.optBoolean("message_actions") == true
    val canEditMessages get() = supportsMessageActions && !hasExecution && runId == null && !submitting && !hasPendingSubmission && !messageActionBusy
    fun forkMessage(message: HermesMessage, onDone: () -> Unit) {
        val session = selectedId ?: return
        if (!supportsMessageActions || message.serverId <= 0 || session in messageActionSessions) return
        messageActionSessions = messageActionSessions + session
        setError(session, null)
        val connection = api
        launch {
            try {
                val request = obj("message_id" to message.serverId, "fork_id" to "mobile_fork_${UUID.randomUUID().toString().replace("-", "")}")
                val result = connection.json("$root/sessions/${q(session)}/fork", request)
                val fork = result.getJSONObject("session")
                if (api !== connection) return@launch
                sessions = mergeSessionPage(listOf(fork), sessions) { it.optString("id") }
                if (selectedId == session) { select(fork); controlMessage = result.optString("warning").takeIf { it.isNotBlank() }; onDone() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { setError(session, "分叉未确认，请先刷新列表检查是否已创建；不会自动再次创建。" + connectionFailureMessage(e)) }
            finally { messageActionSessions = messageActionSessions - session }
        }
    }
    internal fun editMessage(message: HermesMessage, input: String, attached: List<JSONObject>, boundary: MessageEditBoundary, onDone: () -> Unit) {
        val session = selectedId ?: return
        if (!canEditMessages || message.role != "user" || message.serverId <= 0 || !message.editable) return
        val connection = api
        messageActionSessions = messageActionSessions + session; setSubmitting(session, true)
        setError(session, null)
        // Preserve the edited draft before the irreversible request. Unknown outcomes never resend.
        setDraft(session, input); draftFiles = draftFiles + (session to attached)
        launch {
            var rewound = false
            try {
                historyReadMutex.withLock {
                    val result = connection.json("$root/sessions/${q(session)}/rewind", obj("message_id" to message.serverId,
                        "expected_last_turn_id" to boundary.lastTurnId, "expected_latest_user_id" to boundary.latestUserId))
                    if (!result.optBoolean("rewound")) throw java.io.IOException("服务端未确认撤回，编辑内容已保留")
                    rewound = true
                    cachedHistory.remove(session); localSubmissions.remove(session)
                    withContext(Dispatchers.IO) { historyCache.remove(session) }
                    narrations = narrations.filterNot { it.session == session }; saveNarrations()
                    compactionNotices = compactionNotices.filterNot { it.session == session }; saveCompactionNotices()
                    steering = steering.filterNot { it.session == session }; saveSteering()
                    if (selectedId == session) messages = emptyList()
                }
                if (api !== connection || selectedId != session) return@launch
                loadHistory(session)
                setSubmitting(session, false); messageActionSessions = messageActionSessions - session
                onDone()
                if (!sendInput(input.ifBlank { "请查看附件" })) setError(session, "原消息已撤回，编辑草稿已保留，请点击发送")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val prefix = when {
                    rewound -> "原消息已撤回，尚未重发；"
                    e is ApiRequestFailure && e.delivery == "rejected" -> "撤回未执行；"
                    else -> "撤回未确认，不会自动重发；"
                }
                setError(session, prefix + "编辑内容已保留。" + connectionFailureMessage(e))
                if (rewound && selectedId == session) onDone()
            } finally {
                if (session in messageActionSessions) { messageActionSessions = messageActionSessions - session; setSubmitting(session, false) }
            }
        }
    }
    fun isSessionPinned(session: JSONObject) = session.optBoolean("pinned")
    val orderedSessions get() = pinnedSessionsFirst(sessions, ::isSessionPinned)

    /** codex 会话列表显示模式：项目分组 / 最近（平铺）。 */
    var groupByProject by mutableStateOf(prefs.getBoolean("codex_group_by_project", true))
        ; private set
    fun toggleGroupByProject() {
        groupByProject = !groupByProject
        prefs.edit().putBoolean("codex_group_by_project", groupByProject).apply()
    }

    /** codex 项目分组折叠状态（按组名持久化，跨页同名节共享状态）。 */
    private val groupCollapsed = mutableStateOf(prefs.getStringSet("codex_collapsed_groups", setOf("已存档"))?.toSet() ?: setOf("已存档"))
    fun isGroupCollapsed(group: String) = group in groupCollapsed.value
    fun reloadCloudDisplaySettings() {
        groupByProject = prefs.getBoolean("codex_group_by_project", true)
        groupCollapsed.value = prefs.getStringSet("codex_collapsed_groups", setOf("已存档"))?.toSet() ?: setOf("已存档")
    }
    fun toggleSessionGroup(group: String) {
        groupCollapsed.value = if (group in groupCollapsed.value) groupCollapsed.value - group else groupCollapsed.value + group
        prefs.edit().putStringSet("codex_collapsed_groups", groupCollapsed.value).apply()
    }
    fun toggleSessionPin(session: JSONObject) {
        val id = session.optString("id")
        if (id.isBlank() || id in pinningSessions) return
        pinningSessions = pinningSessions + id
        launch {
            try {
                sessionRefreshMutex.withLock {
                val pinned = !isSessionPinned(session)
                val result = api.json("$root/sessions/${q(id)}/pin", obj("pinned" to pinned))
                if (!confirmedSessionPin(result, pinned))
                    throw java.io.IOException("服务端未确认置顶状态，请刷新列表后重试")
                sessions = sessions.map { if (it.optString("id") == id) JSONObject(it.toString()).put("pinned", pinned) else it }
                }
            } finally { pinningSessions = pinningSessions - id }
        }
    }
    private val sessionRefreshMutex = Mutex()
    private val historyReadMutex = Mutex()
    var historyHasMore by mutableStateOf(false)
        private set
    var historyLoadingEarlier by mutableStateOf(false)
        private set
    private var historyOldestId: String? = null

    fun loadEarlierHistory() = launch {
        val id = selectedId ?: return@launch
        val connection = api
        val generation = openingGeneration
        if (!historyHasMore || historyLoadingEarlier) return@launch
        historyLoadingEarlier = true
        try {
            historyReadMutex.withLock {
                if (selectedId == id && api === connection && generation == openingGeneration) loadHistoryNow(id, earlier = true)
            }
        } finally {
            if (selectedId == id && api === connection) historyLoadingEarlier = false
        }
    }
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
    private var openingSession: String? = null
    private var openingGeneration = 0L
    private var contextPrefetchedSession: String? = null
    private var contextPrefetchedAt = 0L
    private var activeRunCheckSession: String? = null
    private var activeRunCheckedAt = 0L
    private var sessionControl by mutableStateOf<JSONObject?>(null)
    private var sessionControlVerifiedAt by mutableLongStateOf(0L)
    val canHandoverSession get() = agent == "codex" && selectedId != null &&
        sessionControl?.optString("state") == "mobile" && activityClock() - sessionControlVerifiedAt < 60_000L
    var controlMessage by mutableStateOf<String?>(null)
        private set
    private var runVerifiedAt by mutableLongStateOf(0L)
    private var runTurnId: String? = null
    val runStateVerified get() = runId != null && activityNow - runVerifiedAt < 30_000L
    val canTakeover get() = agent == "codex" && selectedId != null && capabilities.optBoolean("session_takeover") &&
        runId == null && !submitting && !hasPendingSubmission && (showWriteInterruption(externalRunning, sessionControl?.optString("state"), sessionControlVerifiedAt, activityNow) ||
            error.orEmpty().let { "写入权限" in it || "所有者" in it || "接管" in it })
    val writeAccessMessage get() = sessionControl?.takeIf { showWriteInterruption(externalRunning, it.optString("state"), sessionControlVerifiedAt, activityNow) && it.optString("state") == "busy" }?.optString("message")
    fun dismissControlMessage(expected: String?) { if (controlMessage == expected) controlMessage = null }
    private val observation = ForegroundObservation(::watch, ::pauseWatching)
    private var streamConnected = false
    private var observationEpoch = 0L
    private var statusWake: Channel<Unit>? = null
    fun setObserving(value: Boolean) { observation.setActive(value) }
    fun syncWatchService() {
        val needed = hasKnownActivity || messageQueue.entries.isNotEmpty()
        AgentWatchService.sync(appContext, watchOwner, needed)
    }
    override fun onCleared() {
        AgentWatchService.sync(appContext, watchOwner, false)
        super.onCleared()
    }
    private fun pauseWatching() {
        cancelRecovery()
        observationEpoch++
        watching?.cancel(); watching = null
        streamJob?.cancel(); streamJob = null
        streamConnected = false; statusWake = null
        timingSave?.cancel(); timingSave = null
        // 外部活动轮询也要停：否则退后台后仍以 5~20s 间隔打网络请求。
        externalActivityJob?.cancel(); externalActivityJob = null; externalActivityPollingSession = null
        saveRuns()
    }
    val externalRunning get() = runId == null && externalActivityRunning(externalActivity, selectedId, activityNow, externalVerifiedAt)
    private var compactionRequest by mutableStateOf<Pair<String, Long>?>(null)
    private var streamCompaction by mutableStateOf<Pair<String, Long>?>(null)
    private val streamCompacting get() = streamCompaction?.first == runId && runId != null && runSession == selectedId
    val requestingCompaction get() = compactionRequest?.first == selectedId && compactionRequest != null
    val hasExecution get() = requestingCompaction || (runStateVerified && runSession == selectedId) || externalRunning
    val executionKey get() = if (requestingCompaction) "compact-request-${compactionRequest?.second}" else runId ?: externalActivity?.optString("activity_id")?.takeIf { externalRunning }?.let { "external-$it" }
    val executionTiming get() = if (requestingCompaction) AgentRunTiming(startedAt = compactionRequest?.second) else if (streamCompacting) AgentRunTiming(startedAt = streamCompaction?.second) else if (runId != null) currentRunTiming else externalActivityTiming(externalActivity)
    private var runActivitySnapshot by mutableStateOf<Pair<String, JSONObject>?>(null)
    private var runActivityObservedAt by mutableLongStateOf(0L)
    private var runActivityAttempt: Pair<String, Long>? = null
    private val currentRunActivity get() = runActivitySnapshot?.takeIf {
        it.first == runId && it.second.optString("session_id") == selectedId && activityNow - runActivityObservedAt <= 30_000L
    }?.second
    val executionEvents get() = if (runId != null) {
        val saved = currentRunActivity
        if (saved != null && (!streamConnected || events.isEmpty() || streamGap)) externalActivityEvents(saved).ifEmpty { events } else events
    } else externalActivityEvents(externalActivity)
    val executionEventCount get() = if (runId != null) maxOf(eventCount, currentRunActivity?.optInt("event_count") ?: 0) else externalActivity?.optInt("event_count") ?: 0
    val executionState get() = if (requestingCompaction) "正在提交压缩请求" else if (streamCompacting) "正在压缩上下文" else if (runId != null) state else externalActivityLabel(externalActivity)
    val executionCompacting get() = requestingCompaction || executionState == "正在压缩上下文"

    private var externalActivityJob: Job? = null
    private var externalActivityPollingSession: String? = null

    /** 外部运行（其他客户端发起）时 activity 快照只在打开会话时拉过一次，
     *  导致「距上次响应」冻结、工具数量不变；这里按运行状态自适应轮询。 */
    private fun ensureExternalActivityPolling() {
        val id = selectedId ?: return
        if (externalActivityJob?.isActive == true) {
            if (externalActivityPollingSession == id) return
            externalActivityJob?.cancel()
        }
        externalActivityPollingSession = id
        externalActivityJob = launch {
            while (isActive && selectedId == id) {
                pollExternalActivity()
                val running = externalActivityRunning(externalActivity, id, activityClock(), externalVerifiedAt)
                kotlinx.coroutines.delay(if (running) 5_000L else 20_000L)
            }
        }
    }

    fun pollExternalActivity(force: Boolean = false) = viewModelScope.launch {
        val id = selectedId ?: return@launch
        if (runId != null || submitting) { externalActivity = null; return@launch }
        if (openingSession == id) return@launch
        val connection = api
        val requestedAt = activityClock()
        activityNow = requestedAt
        if (!force && externalActivity != null && requestedAt - externalVerifiedAt < 5_000L) return@launch
        try {
            // Gateway may have recovered after startup; capabilities are safe to retry.
            ensureCapabilities()
            if (api !== connection || selectedId != id) return@launch
            if (capabilities.optJSONObject("chuckie_features")?.optBoolean("external_session_activity") != true) return@launch
            // An IIS/bridge restart can drop the local run subscription while its
            // desktop transport still executes a turn originally submitted here.
            if (activeRunCheckSession != id || requestedAt - activeRunCheckedAt >= 8_000L) attachServerActiveRun(id)
            if (selectedId != id || api !== connection || runId != null) return@launch
            val result = withTimeout(8_000) { connection.json("$root/sessions/${q(id)}/activity") }
            if (selectedId != id || api !== connection || runId != null) return@launch
            applyExternalActivity(id, connection, result, requestedAt)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* Keep only the last verified observation; it expires in 30 seconds. */ }
    }

    private suspend fun applyExternalActivity(id: String, connection: NativeApi, result: JSONObject, observedAt: Long) {
        if (selectedId != id || api !== connection || runId != null || !result.optBoolean("available")) return
        externalActivity = result; externalVerifiedAt = observedAt; activityNow = observedAt
        val compactedAt = parseMessageTimestamp(result.opt("compacted_at"))
        val compactedId = result.optString("compaction_id").takeIf { it.isNotBlank() && it != "null" }
        if (compactedAt != null && compactedId != null) recordExternalCompaction(id, compactedId, compactedAt)
        noteRunActivity(id, obj("status" to if (result.optBoolean("running")) "started" else "completed"), observedAt)
        val revision = "$id:${result.optString("activity_id")}:${result.optLong("revision")}:${result.optBoolean("running")}:${result.optString("kind")}:${result.opt("compacted_at")}"
        if (revision != externalHistoryRevision && historyJob?.isActive != true) {
            // Do not acknowledge the revision before history succeeds: a failed read
            // must be retried even if the agent produces no more events.
            loadHistory(id)
            if (selectedId == id && api === connection) externalHistoryRevision = revision
        }
    }

    private var conversationUi by mutableStateOf(ConversationUiState())
    var draft: String
        get() = conversationUi.entry(selectedId).draft
        set(value) { conversationUi = conversationUi.draft(selectedId, value) }
    private fun setDraft(session: String?, value: String) { conversationUi = conversationUi.draft(session, value) }
    private fun setError(session: String?, value: String?) { conversationUi = conversationUi.error(session, value) }

            /** 服务端确认会话在本机已无记录（thread_missing）：自动退出并清掉本地残留，避免反复同步失败。 */
            private fun exitMissingSession(session: String?) {
                cancelRecovery()
                historyJob?.cancel()
                backgroundRuns.remove(session); if (runSession == session) { runId = null; runSession = null }
                approval = null; pendingText = ""; flushedStreamPrefix = ""; pendingItemId = null; pendingPhase = ""
                events = emptyList(); eventCount = 0; finalSyncJob?.cancel()
                conversationUi = conversationUi.forget(session)
                // 与 deleteSession 同一套本地状态清理：会话已不存在，pending/插话/队列不可能再被
                // 服务端确认，残留会让前台服务和唤醒锁永久持有（队列非空时 syncWatchService 恒 true）。
                if (session != null) {
                    removePending(session)
                    localSubmissions.remove(session)
                    cachedHistory.remove(session)
                    if (finalSyncStates.removeAll { it.session == session }) saveFinalSyncStates()
                    if (outgoingFiles.removeAll { it.session == session }) saveOutgoingFiles()
                    narrations = narrations.filterNot { it.session == session }; saveNarrations()
                    compactionNotices = compactionNotices.filterNot { it.session == session }; saveCompactionNotices()
                    steering = steering.filterNot { it.session == session }; saveSteering()
                    draftFiles = draftFiles - session
                    messageQueue.purgeSession(session)
                    purgeSessionRuntime(session)
                    launch { withContext(Dispatchers.IO) { historyCache.remove(session) } }
                    sessionActivities = sessionActivities - session
                }
                prefs.edit().remove("session").remove("run").remove("runSession").apply()
                selectedId = null
                contextInfo = null; asyncQuestion = null; sessionControl = null
                sessionProvider = ""; sessionModel = ""; sessionEffort = ""; sessionTier = ""
                title = "$agentName 会话"; state = "就绪"
                messages = emptyList(); files = emptyList()
                setError(null, "该会话在本机已无记录（可能已被清理），已自动退出；请从列表选择其他会话")
                launch { refreshSessions() }
            }
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
    private data class LocalSubmission(val pending: PendingAgentSubmission, val existing: Set<Long>, val files: List<JSONObject>)
    private val localSubmissions = mutableMapOf<String, LocalSubmission>()
    private val currentSubmissionKeys = mutableSetOf<String>()
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
    var workspaceResetCredits by mutableStateOf<Map<String, JSONObject>>(emptyMap())
        private set
    var workspaceResetLoading by mutableStateOf<Set<String>>(emptySet())
        private set
    var workspaceResetUsing by mutableStateOf<Set<String>>(emptySet())
        private set
    var workspaceResetErrors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    private var workspaceReadEpoch = 0L
    private var accountReadEpoch = 0L
    var projects by mutableStateOf<List<JSONObject>>(emptyList())
        private set
    var projectsLoading by mutableStateOf(false)
        private set
    var contextInfo by mutableStateOf<JSONObject?>(null)
        private set
    private val contextCache = mutableMapOf<String, JSONObject>()

    /** 每会话最新已知信息（provider/model/effort/tier + 上下文），持久化。进会话先回放缓存，
        避免显示上一个会话的 provider/模型或空上下文；网络刷新后随取随更新。 */
    private fun saveSessionRuntime(id: String, provider: String, model: String, effort: String, tier: String, context: JSONObject?) {
        val cache = runCatching { JSONObject(prefs.getString("sessionRuntimeCache", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val row = (cache.optJSONObject(id) ?: JSONObject())
        row.put("provider", provider); row.put("model", model); row.put("effort", effort); row.put("tier", tier)
        context?.let { row.put("context", it) }
        cache.put(id, row)
        prefs.edit().putString("sessionRuntimeCache", cache.toString()).apply()
    }
    private fun cachedSessionRuntime(id: String): JSONObject? =
        runCatching { JSONObject(prefs.getString("sessionRuntimeCache", "{}") ?: "{}").optJSONObject(id) }.getOrNull()
    private fun noteSessionContext(id: String, used: Long, window: Long) {
        val cache = runCatching { JSONObject(prefs.getString("sessionRuntimeCache", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val row = (cache.optJSONObject(id) ?: JSONObject())
        row.put("context", obj("available" to (used > 0), "tokens" to used, "limit" to window))
        cache.put(id, row)
        prefs.edit().putString("sessionRuntimeCache", cache.toString()).apply()
    }
    private fun purgeSessionRuntime(id: String) {
        val cache = runCatching { JSONObject(prefs.getString("sessionRuntimeCache", "{}") ?: "{}") }.getOrDefault(JSONObject())
        if (cache.remove(id) != null) prefs.edit().putString("sessionRuntimeCache", cache.toString()).apply()
    }
    private fun saveSessionContext(id: String, context: JSONObject) {
        val cache = runCatching { JSONObject(prefs.getString("sessionRuntimeCache", "{}") ?: "{}") }.getOrDefault(JSONObject())
        val row = (cache.optJSONObject(id) ?: JSONObject())
        row.put("context", context)
        cache.put(id, row)
        prefs.edit().putString("sessionRuntimeCache", cache.toString()).apply()
    }
    private fun applyCachedSessionRuntime(id: String) {
        val row = cachedSessionRuntime(id)
        sessionProvider = row?.optString("provider") ?: ""
        sessionModel = row?.optString("model") ?: ""
        sessionEffort = row?.optString("effort") ?: ""
        sessionTier = row?.optString("tier") ?: ""
        updateRuntimeDisplay()
        contextInfo = contextCache[id] ?: row?.optJSONObject("context")
    }
    private var contextReadJob: Job? = null
    private var contextReadSession: String? = null
    var asyncQuestion by mutableStateOf<JSONObject?>(null)
        private set
    // 每会话最近一次成功加载的消息列表（内存级，进程内有效），用于 select() 时先回放再刷新。
    private val cachedHistory = mutableMapOf<String, List<HermesMessage>>()
    private val confirmedMessageKeys = LinkedHashMap<String, String>().apply {
        runCatching { org.json.JSONArray(prefs.getString("confirmedMessageKeys", "[]")).objects() }.getOrDefault(emptyList())
            .forEach { row -> put(row.optString("session") + ":" + row.optLong("id"), row.optString("key")) }
    }
    private fun rememberMessageKey(session: String, id: Long, key: String) {
        confirmedMessageKeys["$session:$id"] = key
        while (confirmedMessageKeys.size > 500) confirmedMessageKeys.remove(confirmedMessageKeys.keys.first())
        prefs.edit().putString("confirmedMessageKeys", org.json.JSONArray(confirmedMessageKeys.map { (identity, local) ->
            obj("session" to identity.substringBeforeLast(':'), "id" to identity.substringAfterLast(':').toLong(), "key" to local)
        }).toString()).apply()
    }
    private fun withConfirmedKeys(session: String, rows: List<HermesMessage>) = rows.map { row ->
        if (row.role == "user" && row.serverId > 0)
            (row.requestKey ?: confirmedMessageKeys["$session:${row.serverId}"])?.let { row.copy(localKey = it) } ?: row
        else row
    }
    private val historyCache = AgentHistoryCache(application.noBackupFilesDir.resolve("conversation-history"), "$agent-$deviceId")
    var historyCacheStatus by mutableStateOf<String?>(null)
    // 会话历史首次加载中（本地无任何数据可回放）：true 时对话区域显示大加载动画。
    var historyLoading by mutableStateOf(false)
        private set
    private fun saveOfflineSessions() {
        prefs.edit().putString("offlineSessions", org.json.JSONArray(sessions.take(50)).toString()).apply()
    }
    private var compactionNotices = if (agent == "codex") {
        prefs.edit().remove("compactionNotices").apply()
        emptyList()
    } else runCatching {
        org.json.JSONArray(prefs.getString("compactionNotices", "[]")).objects().mapNotNull { AgentCompactionNotice.restore(it) }
    }.getOrDefault(emptyList())
    private suspend fun recordCompactionCompletion(run: String, session: String, result: JSONObject) {
        if (agent == "codex") {
            if (successfulCompaction(run, result)) {
                if (selectedId == session) loadHistory(session)
                // 压缩刚完成时 native 压缩行可能还没进 thread/turns（下一次刷新才出现），
                // 先给一次性本地提示行；随后的历史刷新会用 native 行替换它。
                if (selectedId == session && messages.none { it.role == "system" && it.text == "上下文压缩完成" })
                    messages = messages + HermesMessage("system", "上下文压缩完成",
                        localKey = "compaction-$run", timestamp = System.currentTimeMillis())
            }
            return
        }
        if (!successfulCompaction(run, result) || compactionNotices.any { it.run == run }) return
        val time = parseMessageTimestamp(result.opt("completed_at")) ?: parseMessageTimestamp(result.opt("finished_at")) ?: System.currentTimeMillis()
        val started = runTimings[run]?.startedAt
        val history = if (selectedId == session) messages else cachedHistory[session].orEmpty()
        compactionNotices = coalesceCompactionNotices(compactionNotices + AgentCompactionNotice(run, session,
            time, history.lastOrNull { it.serverId > 0 && it.timestamp?.let { at -> at <= time } == true }?.serverId ?: 0,
            startedAt = started))
        saveCompactionNotices()
        val merged = mergeCompactionNotices(history, compactionNotices.filter { it.session == session })
        if (selectedId == session) messages = merged
    }
    private fun saveCompactionNotices() {
        compactionNotices = coalesceCompactionNotices(compactionNotices)
        prefs.edit().putString("compactionNotices", org.json.JSONArray(compactionNotices.map { it.json() }).toString()).apply()
    }
    private fun recordExternalCompaction(session: String, compactedId: String, time: Long) {
        if (agent == "codex") return // History refresh returns the native ordered item.
        val key = "external-$compactedId"
        if (compactionNotices.any { it.session == session && (it.run == key || it.compactionId == compactedId) }) return
        val history = if (selectedId == session) messages else cachedHistory[session].orEmpty()
        val anchor = history.lastOrNull { it.serverId > 0 && it.timestamp?.let { at -> at <= time } == true }?.serverId ?: 0
        val known = compactionNotices.map { note -> note.copy(startedAt = note.startedAt ?: runTimings[note.run]?.startedAt) }
        compactionNotices = coalesceCompactionNotices(known + AgentCompactionNotice(key, session, time, anchor, compactedId))
        saveCompactionNotices()
        val merged = mergeCompactionNotices(history, compactionNotices.filter { it.session == session })
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
        viewModelScope.launch {
            while (isActive) {
                if (this@HermesModel::api.isInitialized && messageQueue.entries.isNotEmpty()) pollMessageQueue().join()
                delay(4000)
            }
        }
    }
    private fun saveRuns() {
        val tracked = backgroundRuns.toMutableMap()
        if (runId != null && runSession != null) tracked[runSession!!] = runId!!
        prefs.edit().putString("trackedRuns", JSONObject(tracked as Map<*, *>).toString()).apply()
        saveRunTimings()
        viewModelScope.launch { syncWatchService() }
    }
    private var pendingSubmissions by mutableStateOf(runCatching {
        val rows = JSONObject(prefs.getString("pendingSubmissions", "{}").orEmpty())
        rows.keys().asSequence().associateWith { id ->
            val row = rows.getJSONObject(id)
            PendingAgentSubmission(row.getString("key"), row.getString("input"), row.array("attachments").let { ids -> (0 until ids.length()).map { ids.getString(it) } }, row.optLong("timestamp"), row.optString("questionId").takeIf { it.isNotBlank() && it != "null" }, row.cleanSetting("modelSettings").takeIf { it.isNotBlank() })
        }.toMutableMap().apply {
            val session = prefs.getString("pendingSession", null)
            val key = prefs.getString("pendingKey", null)
            if (session != null && key != null && !containsKey(session)) put(session, PendingAgentSubmission(key, prefs.getString("pendingInput", "").orEmpty(), org.json.JSONArray(prefs.getString("pendingAttachments", "[]")).let { ids -> (0 until ids.length()).map { ids.getString(it) } }, prefs.getLong("pendingTimestamp", 0), prefs.getString("answeringQuestion", null).takeIf { prefs.getString("answeringQuestionSession", null) == session }))
        }.toMap()
    }.getOrDefault(emptyMap<String, PendingAgentSubmission>()))
    private fun savePending() {
        val rows = JSONObject()
        pendingSubmissions.forEach { (id, pending) -> rows.put(id, obj("key" to pending.key, "input" to pending.input, "attachments" to org.json.JSONArray(pending.attachmentIds), "timestamp" to pending.timestamp, "questionId" to pending.questionId, "modelSettings" to pending.modelSettings)) }
        prefs.edit().putString("pendingSubmissions", rows.toString()).remove("pendingKey").remove("pendingInput").remove("pendingSession").remove("pendingAttachments").remove("pendingTimestamp").apply()
    }
    private fun removePending(session: String) { pendingSubmissions = pendingSubmissions - session; savePending(); syncWatchService() }
    var scrollToLatestRequest by mutableStateOf(0L)
        private set
    private var readConnectionError: String? = null
    private var recoveryJob: Job? = null
    private var recoveryEpoch = 0L
    private var recoveryFailedAt = 0L
    private var stateRecovering by mutableStateOf(false)
    private var statusRecovering by mutableStateOf(false)
    private var streamRecovering by mutableStateOf(false)
    private var contextRecovering by mutableStateOf(false)
    val recovering get() = stateRecovering || statusRecovering || streamRecovering || contextRecovering

    private fun cancelRecovery() {
        recoveryEpoch++
        recoveryJob?.cancel(); recoveryJob = null
        stateRecovering = false; statusRecovering = false; streamRecovering = false; contextRecovering = false
        recoveryFailedAt = 0L
    }

    private fun recoverState(session: String? = selectedId, manual: Boolean = false): Boolean {
        if (!this::api.isInitialized || session != selectedId) return false
        if (recoveryJob?.isActive == true) return true
        if (!manual && recoveryFailedAt != 0L && activityClock() - recoveryFailedAt < 30_000) return false
        val connection = api
        val generation = openingGeneration
        val recovery = ++recoveryEpoch
        stateRecovering = true
        setError(session, null)
        recoveryJob = viewModelScope.launch {
            fun current() = api === connection && selectedId == session && openingGeneration == generation && recoveryEpoch == recovery
            try {
                retryAgentRead {
                    withTimeout(20_000) {
                        if (!current()) throw CancellationException()
                        ensureCapabilities(force = true)
                        if (agent == "codex") fetchAccountsNow()
                        if (!current()) throw CancellationException()
                        refreshSessions()
                        if (session != null) {
                            loadHistory(session)
                            if (!current()) throw CancellationException()
                            loadSelection(session, loadRelated = false)
                            attachServerActiveRun(session, required = true)
                            val context = connection.json("$root/sessions/${q(session)}/context")
                            if (!current()) throw CancellationException()
                            applyContextResult(session, connection, context)
                            reconcilePendingNow(session)
                            if (hasPendingFor(session)) throw IOException("消息发送结果仍待核对，请勿重复发送")
                        }
                    }
                }
                if (current()) {
                    recoveryFailedAt = 0L
                    if (error == readConnectionError) setError(session, null)
                    readConnectionError = null
                    if (state == "正在同步会话") state = "就绪"
                    if (runId != null) watch()
                    ensureExternalActivityPolling()
                }
            } catch (timeout: TimeoutCancellationException) {
                if (current()) {
                    recoveryFailedAt = activityClock()
                    setError(session, "自动同步多次超时，请检查连接后重试；草稿已保留")
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current()) {
                    recoveryFailedAt = activityClock()
                    if (failure is ApiRequestFailure && failure.code == "thread_missing") exitMissingSession(session)
                    else setError(session, "自动同步未成功：${connectionFailureMessage(failure)}")
                }
            } finally { if (current()) stateRecovering = false }
        }
        return true
    }
    private var flushedStreamPrefix = ""
    private var pendingItemId by mutableStateOf<String?>(null)
    private var pendingPhase by mutableStateOf("")
    private var streamGap = false
    private var streamGapRun: String? = null
    private var finalSnapshotReceived by mutableStateOf(false)
    private val finalSyncStates = mutableStateListOf<FinalSyncState>().apply {
        addAll(restoreFinalSyncStates(prefs.getString("finalSyncStates", "[]")))
    }
    private val outgoingFiles = restoreOutgoingFiles(prefs.getString("outgoingFiles", "[]")).toMutableList()
    private val bindingOutgoingRuns = mutableSetOf<String>()
    private fun saveFinalSyncStates() {
        prefs.edit().putString("finalSyncStates", org.json.JSONArray(finalSyncStates.map { it.json() }).toString()).apply()
    }
    private fun discardCompactionReply(run: String, result: JSONObject) {
        val remaining = reconcileCompactionRunSync(finalSyncStates, run, result)
        if (remaining.size != finalSyncStates.size) {
            finalSyncStates.clear(); finalSyncStates.addAll(remaining); saveFinalSyncStates()
        }
        if (isCompactionRun(run, result) && outgoingFiles.removeAll { it.run == run }) saveOutgoingFiles()
    }
    private fun saveOutgoingFiles() {
        prefs.edit().putString("outgoingFiles", org.json.JSONArray(outgoingFiles.map { it.json() }).toString()).apply()
    }
    private fun keepFinalSync(sync: FinalSyncState) {
        val next = retainFinalSync(finalSyncStates, sync)
        finalSyncStates.clear(); finalSyncStates.addAll(next); saveFinalSyncStates()
        if (selectedId == sync.session) messages = projectFinalSyncRows(messages, finalSyncStates, sync.session)
    }
    private fun acknowledgeFinalSync(session: String, history: List<HermesMessage>) {
        val next = reconcileFinalSync(finalSyncStates, session, history)
        if (next.size != finalSyncStates.size) {
            finalSyncStates.clear(); finalSyncStates.addAll(next)
            saveFinalSyncStates()
        }
    }
    val finalSyncPending get() = finalSyncStates.any { it.session == selectedId }
    private fun needsFinalSyncPolling(session: String) = finalSyncStates.any { it.session == session } ||
        outgoingFiles.any { it.session == session }
    private var finalSyncJob: Job? = null
    internal val chatTextStream: ChatTextStream?
        get() = runId?.takeIf { runSession == selectedId && pendingText.isNotBlank() }?.let {
            ChatTextStream(it, pendingItemId, pendingText, pendingTextTimestamp,
                finalSnapshotReceived && pendingPhase == "final_answer", pendingPhase == "commentary")
        }
    internal val assistantRowIdentities: Map<String, String>
        get() = buildMap {
            narrations.filter { it.session == selectedId && it.streamed && it.run != null }.forEach {
                put(it.key, chatAssistantIdentity(it.run!!, it.messageId, it.timestamp))
            }
            finalSyncStates.filter { it.session == selectedId }.forEach {
                put("final-sync-${it.run}", chatAssistantIdentity(it.run, it.item, it.timestamp))
            }
        }
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
    private val checkingSteering = mutableSetOf<String>()
    private fun reconcileSteeringReceipts(session: String, connection: NativeApi) {
        if (agent != "codex") return
        steering.filter { it.session == session && it.delivery == "发送状态待核对" &&
            it.timestamp?.let { at -> System.currentTimeMillis() - at > 120_000L } == true }
            .forEach { record ->
                if (!checkingSteering.add(record.key)) return@forEach
                launch {
                    try {
                        val result = connection.json("$root/runs/lookup?key=${q(record.key)}")
                        if (api !== connection || selectedId != session ||
                            steering.none { it.session == session && it.key == record.key && it.delivery == "发送状态待核对" }) return@launch
                        val sameSession = result.optBoolean("found") && result.optString("session_id") == session
                        when {
                            sameSession && result.optString("kind") == "steering" && result.optString("delivery") == "accepted" -> {
                                updateSteering(record.key, "已送达")
                                loadHistory(session)
                            }
                            sameSession && result.optString("kind") == "steering" && result.optString("delivery") == "rejected" ||
                                !result.optBoolean("found") -> {
                                steering = steering.filterNot { it.session == session && it.key == record.key }
                                saveSteering()
                                messages = messages.filterNot { it.localKey == record.key }
                                setError(session, "未写入的插话已从本机待核对列表清除")
                            }
                        }
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Keep the pending receipt until the lookup succeeds. */ }
                    finally { checkingSteering.remove(record.key) }
                }
            }
    }
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
        val nativeRows = messages.filterNot { it.serverId == 0L && it.localKey?.startsWith("narration-") == true }
        messages = withActiveNarrations(nativeRows, liveNarrations(session), run)
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
    /** Only the currently tracked task supplies temporary narration. */
    private fun liveNarrations(id: String) = narrations.filter {
        it.session == id && it.run != null && it.run == (runId.takeIf { runSession == id } ?: backgroundRuns[id])
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
        discardRunNarrations(id)
        runNarrationUsers.remove(id); saveRuns()
    }
    private fun discardRunNarrations(run: String) {
        val keys = narrations.filter { it.run == run }.map { it.key }.toSet()
        narrations = narrations.filterNot { it.run == run }; saveNarrations()
        messages = messages.filterNot { it.serverId == 0L && it.localKey in keys }
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
            sessionControl = null; sessionControlVerifiedAt = 0
            state = "就绪"; controlMessage = result.optString("message")
            loadHistory(session); loadSelection(session)
        } finally { setSubmitting(session, false) }
    }
    fun pollContext(): Job {
        val id = selectedId ?: return viewModelScope.launch { }
        if (openingSession == id || contextPrefetchedSession == id && activityClock() - contextPrefetchedAt < 5_000L)
            return viewModelScope.launch { }
        contextReadJob?.takeIf { it.isActive && contextReadSession == id }?.let { return it }
        contextReadJob?.cancel()
        contextReadSession = id
        val connection = api
        val generation = openingGeneration
        return viewModelScope.launch {
        if (contextInfo == null) contextInfo = obj("available" to false, "message" to "正在读取")
        try {
            val result = retryAgentRead {
                try { withTimeout(15_000) { connection.json("$root/sessions/${q(id)}/context") } }
                catch (failure: Exception) {
                    if (canRecoverAgentRead(failure)) contextRecovering = true
                    throw failure
                }
            }
            if (selectedId != id || api !== connection || generation != openingGeneration) return@launch
            applyContextResult(id, connection, result)
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (selectedId == id && api === connection && generation == openingGeneration && contextInfo?.optBoolean("available") != true)
                contextInfo = obj("available" to false, "message" to "多次读取超时，可稍后重试")
        } catch (e: CancellationException) { throw e }
        catch (error: Exception) {
            if (selectedId == id && api === connection && generation == openingGeneration && contextInfo?.optBoolean("available") != true)
                contextInfo = obj("available" to false, "message" to "读取暂未恢复，可稍后重试")
        } finally { if (api === connection && generation == openingGeneration) contextRecovering = false }
        }.also { contextReadJob = it }
    }
    private fun applyContextResult(id: String, connection: NativeApi, result: JSONObject, prefetched: Boolean = false) {
        if (selectedId != id || api !== connection) return
        contextInfo = result.optJSONObject("context") ?: result
        if (contextInfo?.optBoolean("available") == true) contextCache[id] = contextInfo!!
        saveSessionContext(id, contextInfo!!)
        if (agent == "codex") asyncQuestion = visibleQuestion(result.optJSONObject("question"), id)
        sessionControl = result.optJSONObject("control")
        sessionControlVerifiedAt = activityClock()
        result.optString("title").takeIf { it.isNotBlank() && it != "null" }?.let { title = it }
        if (prefetched) { contextPrefetchedSession = id; contextPrefetchedAt = activityClock() }
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
        val started = System.currentTimeMillis()
        val pendingCompaction = id to started
        compactionRequest = pendingCompaction
        setError(id, null)
        try {
            val request = api.request("$root/sessions/${q(id)}/compact", obj()).newBuilder().header("Idempotency-Key", UUID.randomUUID().toString()).build()
            val result = api.json(request)
            startRunTiming(result.getString("run_id"), result, started)
            noteRunActivity(id, obj("status" to result.optString("status", "started")), activityClock())
            if (selectedId != id) {
                backgroundRuns[id] = result.getString("run_id"); saveRuns()
                return@launch
            }
            runId = result.getString("run_id"); runSession = id; seq = -1; events = emptyList(); eventCount = 0; pendingText = ""; approval = null; state = "正在压缩上下文"
            runVerifiedAt = activityClock(); activityNow = runVerifiedAt
            prefs.edit().putString("run", runId).putString("runSession", id).remove("runMessageKey").apply()
            saveRuns(); watch()
        } finally {
            if (compactionRequest == pendingCompaction) compactionRequest = null
            setSubmitting(id, false)
        }
    }
    fun fetchProjects() = launch {
        projectsLoading = true; error = null
        try { projects = api.json("$root/projects").array("data").objects() }
        finally { projectsLoading = false }
    }

    fun bind(value: NativeApi) {
        if (this::api.isInitialized && api.base == value.base) return
        cancelRecovery()
        accountReadEpoch++
        sessionSearchEpoch++; sessionSearchJob?.cancel()
        sessionSearchResults = emptyList(); sessionSearchLoading = false; sessionSearchError = null; sessionSearchHasMore = false
        historyOldestId = null; historyHasMore = false; historyLoadingEarlier = false
        filesReadEpoch++; filesReadJob?.cancel(); filesReadJob = null; filesRefreshPending = false
        loginJob?.cancel(); loginJob = null; loginInfo = null; loginVisible = false; loginStarting = false; loginError = null
        workspaceReadEpoch++; workspaceUsages = emptyMap(); workspaceUsageLoading = emptySet()
        workspaceResetCredits = emptyMap(); workspaceResetLoading = emptySet(); workspaceResetUsing = emptySet(); workspaceResetErrors = emptyMap()
        openingGeneration++; openingSession = null; contextPrefetchedSession = null; activeRunCheckSession = null
        api = value.withReadPolicy(noRetry = true, onReadSuccess = {
            if (api.base == value.base) {
                if (error == readConnectionError) error = null
                readConnectionError = null
            }
        })
        if (sessionSearchQuery.isNotEmpty()) searchSessions(sessionSearchQuery)
        sessionActivities = emptyMap()
        contextCache.clear()
        contextReadJob?.cancel(); contextReadJob = null; contextReadSession = null
        externalActivity = null; externalHistoryRevision = null
        watching?.cancel()
        streamJob?.cancel()
        launch {
            selectedId?.let { session ->
                if (messages.isEmpty()) {
                    val offline = (cachedHistory[session] ?: withContext(Dispatchers.IO) { historyCache.read(session) })?.let { withConfirmedKeys(session, it) }
                    if (selectedId == session && offline != null) {
                        cachedHistory[session] = offline; messages = offline
                        historyCacheStatus = null
                        sessions.firstOrNull { it.optString("id") == session }?.optString("title")?.takeIf { it.isNotBlank() }?.let { title = it }
                    }
                }
            }
            // Restore the offline view first; optional catalogs must not gate the online read.
            selectedId?.let { id ->
                val session = sessions.firstOrNull { it.optString("id") == id }
                    ?: JSONObject().put("id", id).put("title", title)
                select(session)
            }
            capabilities = api.json("$root/capabilities")
            if (agent == "hermes" && capabilities.optJSONObject("features")?.optBoolean("run_submission") != true)
                throw java.io.IOException("当前 $agentName 不支持可恢复任务接口")
            refreshSessions()
            reconcilePendingNow()
            fetchModels()
            if (agent == "codex") fetchAccountsNow()
            if (runId != null) watch()
            else if (hasPendingSubmission && agent == "hermes") error = "上次任务提交结果尚未确认，请使用原标识核对并重试"
            syncWatchService()
        }
    }

    private suspend fun ensureCapabilities(force: Boolean = false) {
        if (!this::api.isInitialized || !force && capabilities.optJSONObject("chuckie_features")?.optBoolean("external_session_activity") == true) return
        val connection = api
        val result = connection.json("$root/capabilities")
        if (api === connection) capabilities = result
    }

    private fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        val originatingSession = selectedId
        val originatingConnection = if (this::api.isInitialized) api else null
        return viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (originatingConnection !== api) return@launch
                if (canRecoverAgentRead(e) && recoverState(originatingSession)) return@launch
                if (e is ApiRequestFailure && e.code == "thread_missing" && selectedId == originatingSession) exitMissingSession(originatingSession)
                setError(originatingSession, connectionFailureMessage(e))
                if (selectedId == originatingSession && historyCacheStatus != null) historyCacheStatus = "缓存 · 同步失败"
                if (e is ReadConnectionFailure && selectedId == originatingSession) readConnectionError = error
            }
        }
    }

    fun refresh() = launch {
        ensureCapabilities(force = true)
        if (agent == "codex") fetchAccountsNow()
        refreshSessions()
        selectedId?.let { loadHistory(it) }
    }

    private suspend fun refreshSessions(offset: Int = 0) = sessionRefreshMutex.withLock {
        val connection = api
        val requestedAt = activityClock()
        loading = true
        var pageRows: List<JSONObject> = emptyList()
        try {
            val result = connection.json(if (agent == "codex") "$root/sessions" +
                (if (offset > 0 && nextCursor != null) "?cursor=${q(nextCursor!!)}" else "")
                else "$root/sessions?offset=$offset")
            if (api !== connection) return@withLock
            val rows = result.array("data").objects()
            pageRows = rows
            noteSessionActivities(rows, requestedAt)
            val merged = inheritSessionContext(sessions, rows)
            sessions = mergeSessionPage(
                if (offset == 0) emptyList() else sessions,
                merged,
            ) { it.optString("id") }
            saveOfflineSessions()
            hasMore = result.optBoolean("has_more")
            // Hermes back-fills pins beyond the page: visible row count is not a page offset.
            nextSessionOffset = result.optInt("offset", offset) + result.optInt("limit", 50)
            nextCursor = result.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
        } finally {
            loading = false
        }
        // 上下文占用懒加载：列表先渲染，缺数据的行再分块异步补（服务端按缓存/预算返回），
        // 不阻塞列表本身；codex 的列表响应已内联该字段，无需请求。
        if (agent != "codex") launch { loadSessionContext(pageRows) }
    }

    /** 列表响应经常不带 context（服务端对运行中会话 30s 过期后不下发，其余走懒加载）；
        合并前从现有行继承，避免进度条闪失。新行有值时不覆盖。 */
    private fun inheritSessionContext(existing: List<JSONObject>, incoming: List<JSONObject>): List<JSONObject> {
        val known = existing.associate {
            it.optString("id") to (it.optLong("context_used", 0) to it.optLong("context_window", 0))
        }.filter { it.value.first > 0 }
        if (known.isEmpty()) return incoming
        return incoming.map { row ->
            val prev = known[row.optString("id")]
            if (prev != null && row.optLong("context_used", 0) <= 0)
                JSONObject(row.toString()).apply { put("context_used", prev.first); put("context_window", prev.second) }
            else row
        }
    }

    private suspend fun loadSessionContext(rows: List<JSONObject>) {
        val connection = api
        // 快速滑动时多页并发拉取会拖慢网关，服务端预算内超时的 id 不在响应里——
        // 对缺失的 id 逐轮重试（最多 3 轮），单块失败不中断后续块。
        // 不再永久标记 context_unavailable：繁忙会话偶发空结果后仍要在下次列表刷新时重试
        var missing = rows.filter { it.optLong("context_used", 0) <= 0 }
            .map { it.optString("id") }.filter { it.isNotBlank() }.distinct()
        var attempt = 0
        while (missing.isNotEmpty() && attempt < 3 && api === connection) {
            val retry = mutableListOf<String>()
            for (chunk in missing.chunked(20)) {
                val answered = try {
                    connection.json("$root/sessions/context-bulk?ids=" + chunk.joinToString(",") { q(it) })
                } catch (error: Exception) {
                    if (api !== connection) return
                    null
                }
                if (api !== connection) return
                if (answered == null) { retry += chunk; continue }
                val updates = mutableMapOf<String, Pair<Long, Long>>()
                val empty = mutableListOf<String>()
                for (id in chunk) {
                    val entry = answered.optJSONObject(id)
                    val used = entry?.optLong("context_used", 0) ?: 0
                    val window = entry?.optLong("context_window", 0) ?: 0
                    if (used > 0 && window > 0) updates[id] = used to window
                    else if (entry != null) empty += id
                    else retry += id
                }
                if (updates.isEmpty() && empty.isEmpty()) continue
                updates.forEach { (id, pair) -> noteSessionContext(id, pair.first, pair.second) }
                sessions = sessions.map { session ->
                    val id = session.optString("id")
                    when {
                        updates.containsKey(id) -> JSONObject(session.toString()).apply {
                            val (used, window) = updates.getValue(id)
                            put("context_used", used); put("context_window", window)
                        }
                        else -> session
                    }
                }
            }
            missing = retry.distinct()
            attempt++
            if (missing.isNotEmpty() && attempt < 3 && api === connection) delay(1500)
        }
    }

    fun moreSessions() = launch { refreshSessions(if (agent == "codex") sessions.size else nextSessionOffset) }
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
        if (session == null) return
        val pending = pendingSubmissions[session] ?: return
        val connection = api
        val result = if (agent == "hermes") {
            try { connection.json("$root/runs/lookup?key=${q(pending.key)}&session_id=${q(session)}") }
            catch (e: ApiRequestFailure) {
                if (e.status != 404) throw e
                if (selectedId == session) loadHistory(session)
                return
            }
        } else connection.json("$root/runs/lookup?key=${q(pending.key)}")
        if (api !== connection) return
        if (agent == "hermes" && !result.optBoolean("found")) {
            if (selectedId == session) loadHistory(session)
            if (hasPendingFor(session)) setError(session, "未能确认上一条消息。请核对历史，或清除本机待核对记录后继续；不会自动重发。")
            return
        }
        if (pendingSubmissions[session]?.key != pending.key || !result.optBoolean("found")) return
        noteRunActivity(session, result, activityClock())
        if (agent == "hermes") {
            if (result.optString("status") !in setOf("started", "submitting", "running", "queued", "stopping", "waiting_for_approval", "completed", "failed", "cancelled", "interrupted")) {
                setError(session, "Hermes 尚未确认上次任务状态，请稍后核对；不会重复发送。")
                return
            }
            markSubmissionAccepted(session, pending.key); removePending(session); questionAccepted(session)
            setError(session, null)
            if (result.optString("status") in setOf("started", "submitting", "running", "queued", "stopping", "waiting_for_approval")) {
                val id = result.getString("run_id")
                startRunTiming(id, result, pending.timestamp)
                if (selectedId == session) {
                    runId = id; runSession = session; seq = -1; events = emptyList(); eventCount = 0; approval = null; pendingText = ""
                    runVerifiedAt = activityClock(); activityNow = runVerifiedAt; state = "执行中"
                    prefs.edit().putString("run", id).putString("runSession", session).putString("runMessageKey", pending.key).apply()
                    watch()
                } else backgroundRuns[session] = id
                saveRuns()
            }
            if (selectedId == session) loadHistory(session)
            return
        }
        when (result.optString("status")) {
            "started", "submitting" -> {
                if (result.optString("delivery") == "unknown") {
                    setError(session, "任务已登记，消息是否写入仍待核对；不会自动重发")
                    return
                }
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
            "completed" -> {
                markSubmissionAccepted(session, pending.key); removePending(session); questionAccepted(session)
                if (selectedId == session) loadHistory(session)
            }
            "failed", "interrupted", "cancelled" -> {
                when (result.optString("delivery")) {
                    "accepted" -> {
                        markSubmissionAccepted(session, pending.key); removePending(session); questionAccepted(session)
                        if (selectedId == session) loadHistory(session)
                    }
                    "rejected" -> {
                        removePending(session); localSubmissions.remove(session)
                        if (selectedId == session) messages = messages.filterNot { it.localKey == pending.key }
                        if (conversationUi.entry(session).draft.isBlank() && pending.questionId == null) setDraft(session, pending.input)
                    }
                    else -> setError(session, "任务已结束，但消息是否写入仍待核对；不会自动重发")
                }
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
                val latest = mergeSessionPage(emptyList(),
                    inheritSessionContext(sessions, connection.json("$root/sessions").array("data").objects())) { it.optString("id") }
                if (api !== connection) return@launch
                noteSessionActivities(latest, requestedAt)
                // The server page is sorted by last activity; adopting it wholesale keeps
                // recently active sessions moving to the top instead of freezing positions.
                val indexed = latest.associateBy { it.optString("id") }
                sessions = latest + sessions.filter { it.optString("id") !in indexed }.map {
                    // Page one back-fills every native pin. Absence clears stale cached pins
                    // after another client unpins, archives or deletes an older conversation.
                    if (isSessionPinned(it)) JSONObject(it.toString()).put("pinned", false) else it
                }
            reconcilePendingNow()
            for ((session, id) in backgroundRuns.toMap()) {
                try {
                    val runRequestedAt = activityClock()
                    val result = connection.json("$root/runs/$id")
                    if (api !== connection) return@launch
                    if (backgroundRuns[session] != id) continue
                    noteRunActivity(session, result, runRequestedAt)
                    if (result.optString("status") in setOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown") && backgroundRuns[session] == id) {
                        recordCompactionCompletion(id, session, result)
                        discardRunNarrations(id)
                        runNarrationUsers.remove(id)
                        backgroundRuns.remove(session); saveRuns()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: ApiRequestFailure) {
                    if (e.status == 404 && backgroundRuns[session] == id && api === connection) {
                        discardRunNarrations(id)
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

    /** codex 用量统一存储：会话顶部（/usage）与工作空间对话框（/workspaces/{id}/usage）
        两个来源各自成功后都把额度窗口写进共享状态，避免两处短暂显示不一致。 */
    private fun adoptUsageRateLimits(target: JSONObject, source: JSONObject) {
        listOf("rateLimits", "rateLimitsByLimitId").forEach { key ->
            source.optJSONObject(key)?.let { target.put(key, it) }
        }
    }

    private suspend fun fetchAccountsNow() {
        val connection = api
        val epoch = ++accountReadEpoch
        val loadedAccounts = connection.json("$root/accounts")
        val loadedWorkspaces = connection.json("$root/workspaces")
        if (api !== connection || epoch != accountReadEpoch) return
        if (workspaces.optString("current") != loadedWorkspaces.optString("current")) {
            workspaceReadEpoch++; workspaceUsages = emptyMap(); workspaceUsageLoading = emptySet()
            workspaceResetCredits = emptyMap(); workspaceResetLoading = emptySet(); workspaceResetUsing = emptySet(); workspaceResetErrors = emptyMap()
        }
        accounts = loadedAccounts; workspaces = loadedWorkspaces
        workspaceUsages = workspaceUsages.filterKeys { id -> workspaces.array("data").objects().any { it.optString("id") == id } }
        workspaceResetCredits = workspaceResetCredits.filterKeys { id -> workspaces.array("data").objects().any { it.optString("id") == id } }
        workspaceResetErrors = workspaceResetErrors.filterKeys { id -> workspaces.array("data").objects().any { it.optString("id") == id } }
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
                        val delivered = if (result.optBoolean("available") && receivedWorkspace.isNotBlank() && receivedWorkspace != expectedWorkspace)
                            obj("available" to false, "error" to "返回的工作空间身份不匹配，请刷新") else result
                        workspaceUsages = workspaceUsages + (id to delivered)
                        // 统一存储：当前工作空间的额度窗口反写会话顶部用量。
                        if (delivered.optBoolean("available") && id == workspaces.optString("current"))
                            delivered.optJSONObject("usage")?.let { quotaUsage ->
                                usage = JSONObject(usage?.toString() ?: "{}").also { adoptUsageRateLimits(it, quotaUsage) }
                            }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { if (api === connection && epoch == workspaceReadEpoch) workspaceUsages = workspaceUsages + (id to obj("available" to false, "error" to "暂时无法读取用量，请刷新重试")) }
                finally { if (api === connection && epoch == workspaceReadEpoch) workspaceUsageLoading = workspaceUsageLoading - id }
            }
        }
    }
    fun fetchAccounts() = launch { error = null; fetchAccountsNow() }

    fun fetchWorkspaceResetCredits(id: String) {
        if (agent != "codex" || id.isBlank() || id in workspaceResetLoading || id in workspaceResetUsing) return
        workspaceResetLoading = workspaceResetLoading + id
        workspaceResetErrors = workspaceResetErrors - id
        val connection = api
        val epoch = workspaceReadEpoch
        val expectedWorkspace = workspaces.array("data").objects().firstOrNull { it.optString("id") == id }?.optString("workspace_id").orEmpty()
        viewModelScope.launch {
            try {
                val result = connection.json("$root/workspaces/${q(id)}/rate-limit-resets")
                if (api === connection && epoch == workspaceReadEpoch) {
                    val receivedWorkspace = result.optString("chatgpt_account_id")
                    if (!result.optBoolean("available") || expectedWorkspace.isBlank() || receivedWorkspace != expectedWorkspace)
                        throw java.io.IOException("返回的工作空间身份不匹配，请刷新")
                    workspaceResetCredits = workspaceResetCredits + (id to result)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (api === connection && epoch == workspaceReadEpoch) workspaceResetErrors = workspaceResetErrors + (id to connectionFailureMessage(e))
            } finally {
                if (api === connection && epoch == workspaceReadEpoch) workspaceResetLoading = workspaceResetLoading - id
            }
        }
    }

    fun consumeWorkspaceResetCredit(id: String, creditId: String?) {
        if (agent != "codex" || id.isBlank() || id in workspaceResetUsing || id in workspaceResetLoading) return
        workspaceResetUsing = workspaceResetUsing + id
        workspaceResetErrors = workspaceResetErrors - id
        val connection = api
        val epoch = workspaceReadEpoch
        val expectedWorkspace = workspaces.array("data").objects().firstOrNull { it.optString("id") == id }?.optString("workspace_id").orEmpty()
        viewModelScope.launch {
            try {
                val body = JSONObject().apply {
                    if (!creditId.isNullOrBlank()) put("creditId", creditId) else put("useNextAvailable", true)
                }
                val request = connection.request("$root/workspaces/${q(id)}/rate-limit-resets/consume", body).newBuilder()
                    .header("Idempotency-Key", UUID.randomUUID().toString()).build()
                val result = connection.json(request)
                if (api === connection && epoch == workspaceReadEpoch) {
                    val receivedWorkspace = result.optString("chatgpt_account_id")
                    if (!result.optBoolean("available") || expectedWorkspace.isBlank() || receivedWorkspace != expectedWorkspace)
                        throw java.io.IOException("返回的工作空间身份不匹配，请刷新")
                    workspaceResetCredits = workspaceResetCredits + (id to result)
                    result.optJSONObject("usage")?.let { refreshed ->
                        val wrapper = workspaceUsages[id]?.let { JSONObject(it.toString()) }
                            ?: obj("workspace_id" to id, "chatgpt_account_id" to receivedWorkspace, "available" to true)
                        wrapper.put("usage", refreshed).put("checked_at", result.optLong("checked_at"))
                        workspaceUsages = workspaceUsages + (id to wrapper)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (api === connection && epoch == workspaceReadEpoch) workspaceResetErrors = workspaceResetErrors + (id to connectionFailureMessage(e))
            } finally {
                if (api === connection && epoch == workspaceReadEpoch) workspaceResetUsing = workspaceResetUsing - id
            }
        }
    }

    private fun currentCodexAccountId(): String? = if (agent == "codex") currentCodexIdentity(accounts, workspaces) else null

    private suspend fun resetAccountView(expectedAccount: String? = null) {
        cancelRecovery()
        historyJob?.cancel()
        openingGeneration++; openingSession = null
        contextReadJob?.cancel(); contextReadJob = null; contextReadSession = null
        contextPrefetchedSession = null; activeRunCheckSession = null
        selectedId = null
        contextInfo = null; asyncQuestion = null
        sessionProvider = ""; sessionModel = ""; sessionEffort = ""; sessionTier = ""
        messages = emptyList()
        files = emptyList()
        pendingFiles = emptyList()
        draft = ""
        title = agentName
        prefs.edit().remove("session").apply()
        val attempts = if (expectedAccount == null) 1 else 4
        var identitySynced = false
        for (attempt in 0 until attempts) {
            fetchAccountsNow()
            if (expectedAccount == null || currentCodexAccountId() == expectedAccount) { identitySynced = true; break }
            if (attempt < 3) delay(250L * (attempt + 1))
        }
        if (!identitySynced)
            throw java.io.IOException("工作空间已切换，但手机尚未同步电脑身份，请刷新后重试")
        refreshSessions()
        fetchModels()
    }
    fun switchAccount(id: String, workspace: Boolean, onDone: () -> Unit) = launch {
        if (switchingAccount) return@launch
        switchingAccount = true
        accountReadEpoch++ // Reject account reads begun before the switch.
        error = null
        try {
            val switched = api.json("$root/${if (workspace) "workspaces" else "accounts"}/$id/use", obj())
            if (switched.optString("current") != id) throw java.io.IOException("服务端未确认工作空间切换")
            // Bind subsequent queue/send requests to the acknowledged identity immediately;
            // fetchAccountsNow below then verifies the authoritative server view.
            accounts = JSONObject(accounts.toString()).put("current", id)
            workspaces = JSONObject(workspaces.toString()).put("current", id)
            messageQueue.pause(true)
            watching?.cancel(); streamJob?.cancel()
            runId = null; runSession = null; approval = null; usage = null
            sessionActivities = emptyMap()
            backgroundRuns.clear(); saveRuns(); pendingSubmissions = emptyMap(); savePending(); conversationUi = ConversationUiState(); draftFiles = emptyMap(); localSubmissions.clear()
            runNarrationUsers.clear()
            workspaceReadEpoch++; workspaceUsages = emptyMap(); workspaceUsageLoading = emptySet()
            workspaceResetCredits = emptyMap(); workspaceResetLoading = emptySet(); workspaceResetUsing = emptySet(); workspaceResetErrors = emptyMap()
            prefs.edit().remove("run").remove("runSession").remove("pendingKey").remove("pendingInput").remove("pendingSession").apply()
            resetAccountView(id)
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
        try { val result = connection.json("$root/usage"); if (api === connection) {
            usage = result
            // 统一存储：当前工作空间的用量行同步额度窗口，切进对话框时显示同一份数据。
            val current = workspaces.optString("current")
            if (current.isNotBlank()) workspaceUsages[current]?.let { row ->
                val rowUsage = (row.optJSONObject("usage") ?: JSONObject()).also { adoptUsageRateLimits(it, result) }
                workspaceUsages = workspaceUsages + (current to JSONObject(row.toString()).put("usage", rowUsage))
            }
        } }
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
        cancelRecovery()
        val id = session.getString("id")
        val opening = ++openingGeneration
        filesReadEpoch++; filesReadJob?.cancel(); filesReadJob = null; filesRefreshPending = false
        openingSession = id
        contextPrefetchedSession = null
        activeRunCheckSession = null
        contextReadJob?.cancel(); contextReadJob = null; contextReadSession = null
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
            finalSyncJob?.cancel()
            state = "就绪"; readConnectionError = null; files = emptyList()
            prefs.edit().remove("run").remove("runSession").apply()
        }
        selectedId = id
        if (finalSyncStates.any { it.session == id }) state = "已结束，正在同步消息"
        historyOldestId = null
        historyHasMore = false
        historyLoadingEarlier = false
        title = session.optString("title").ifBlank { "未命名会话" }
        prefs.edit().putString("session", selectedId).apply()
        historyJob?.cancel()
        applyCachedSessionRuntime(id)
        asyncQuestion = null
        // 先回放上一次该会话的消息（如有缓存），网络刷新到位后替换 —— 消除「返回再进白屏等待」。
        cachedHistory[selectedId]?.let { cached ->
            messages = projectFinalSyncRows(cached, finalSyncStates, id)
            historyCacheStatus = null; historyLoading = false
            // 回放缓存后请求滚到底，否则打开会话停在缓存顶部等网络刷新
            scrollToLatestRequest++
        } ?: run { messages = projectFinalSyncRows(emptyList(), finalSyncStates, id); historyCacheStatus = null; historyLoading = true }
        // 恢复该会话的后台 run 跟踪
        backgroundRuns.remove(id)?.let { resumed ->
            runId = resumed; runSession = id
            prefs.edit().putString("run", resumed).putString("runSession", id).apply()
            watch()
        }
        saveRuns()
        ensureExternalActivityPolling()
        historyJob = launch {
            val connection = api
            // Start the network read before disk-cache replay; neither should block the other.
            val overviewRequest = async {
                try { withTimeout(25_000) { connection.json("$root/sessions/${q(id)}/overview?limit=100") } }
                catch (_: TimeoutCancellationException) { null }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { null } // Older servers: retain the original per-endpoint path.
            }
            try {
                if (id !in cachedHistory) {
                    val offline = withContext(Dispatchers.IO) { historyCache.read(id) }?.let { withConfirmedKeys(id, it) }
                    if (selectedId != id || api !== connection) return@launch
                    if (offline != null && messages.isEmpty()) {
                        cachedHistory[id] = offline; messages = projectFinalSyncRows(offline, finalSyncStates, id)
                        historyCacheStatus = null; historyLoading = false; scrollToLatestRequest++
                    }
                }
                val overview = overviewRequest.await()?.let(SessionOverview::read)
                if (selectedId != id || api !== connection || openingGeneration != opening) return@launch
                if (openingGeneration == opening) openingSession = null
                if (overview != null) {
                    val context = overview.context
                    if (context != null) applyContextResult(id, connection, context, prefetched = true) else pollContext()
                    attachServerActiveRun(id, prefetched = overview.activeRun)
                    if (selectedId != id || api !== connection || openingGeneration != opening) return@launch
                    if (runId == null) {
                        val activity = overview.activity
                        if (activity != null) applyExternalActivity(id, connection, activity, activityClock())
                        else pollExternalActivity(force = true)
                    }
                    this@HermesModel.launch { loadSelection(id, overview.detail, loadRelated = false) }
                    loadHistory(id, overview.history, overview.files)
                } else {
                    pollContext()
                    this@HermesModel.launch { loadSelection(id) }
                    loadHistory(id)
                }
                if (needsFinalSyncPolling(id)) ensureFinalSyncPolling(id, api)
                this@HermesModel.launch { reconcilePendingNow(id) }
            } finally {
                if (openingGeneration == opening) openingSession = null
            }
        }
    }

    /** 多端共享：本地没有跟踪时，向服务端查询该会话是否有其它设备发起的活跃 run，有则以观察者身份挂载。 */
    private suspend fun attachServerActiveRun(id: String, allowSubmitting: Boolean = false, required: Boolean = false, prefetched: JSONObject? = null) {
        if (runId != null || submitting && !allowSubmitting) return
        val connection = api
        val generation = openingGeneration
        try {
            val result = prefetched?.takeIf { it.has("run_id") } ?: connection.json("$root/sessions/${q(id)}/active-run")
            if (selectedId != id || api !== connection || generation != openingGeneration || runId != null) return
            activeRunCheckSession = id; activeRunCheckedAt = activityClock()
            val serverRun = result.optString("run_id").takeIf { it.isNotBlank() && it != "null" } ?: return
            if (result.optString("status") !in setOf("started", "submitting", "running", "queued", "stopping", "waiting_for_approval")) return
            startRunTiming(serverRun, result)
            runId = serverRun; runSession = id
            seq = -1; events = emptyList(); eventCount = 0; approval = null; pendingText = ""
            prefs.edit().putString("run", serverRun).putString("runSession", id).apply()
            state = statusLabel(result.optString("status"))
            runVerifiedAt = activityClock(); activityNow = runVerifiedAt
            noteRunActivity(id, result, runVerifiedAt)
            saveRuns(); watch()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { if (required) throw e }
    }
    private suspend fun loadSelection(id: String, prefetched: JSONObject? = null, loadRelated: Boolean = true) {
        val connection = api
        val generation = openingGeneration
        val response = prefetched?.takeIf { it.optJSONObject("session") != null || it.has("id") }
            ?: connection.json("$root/sessions/$id")
        val result = response.optJSONObject("session") ?: response.takeIf { it.has("id") } ?: return
        if (selectedId != id || api !== connection || generation != openingGeneration) return
        result.optString("title").takeIf { it.isNotBlank() }?.let { title = it }
        val selection = readAgentSelection(result)
        sessionProvider = selection.provider; sessionModel = selection.model
        sessionEffort = selection.effort; sessionTier = selection.tier
        updateRuntimeDisplay()
        saveSessionRuntime(id, sessionProvider, sessionModel, sessionEffort, sessionTier, result.optJSONObject("context"))
        if (loadRelated) {
            result.optJSONObject("context")?.let { contextInfo = it; if (it.optBoolean("available")) contextCache[id] = it }
            asyncQuestion = visibleQuestion(result.optJSONObject("question"), id)
            sessionControl = result.optJSONObject("control")
            sessionControlVerifiedAt = activityClock()
            pollContext(); attachServerActiveRun(id)
        }
    }

    private suspend fun loadHistory(id: String, prefetched: JSONObject? = null, prefetchedFiles: JSONObject? = null) {
        val connection = api
        val generation = openingGeneration
        historyReadMutex.withLock {
            if (id == selectedId && api === connection && generation == openingGeneration)
                loadHistoryNow(id, prefetched = prefetched, prefetchedFiles = prefetchedFiles)
        }
    }
    private suspend fun loadHistoryNow(id: String, earlier: Boolean = false, prefetched: JSONObject? = null, prefetchedFiles: JSONObject? = null) {
        val connection = api
        val generation = openingGeneration
        val response = try {
            (prefetched?.takeIf { !earlier && it.optJSONArray("data") != null } ?: connection.json("$root/sessions/$id/messages?limit=100" +
                (historyOldestId?.let { "&${if (earlier) "older_before" else "from_id"}=" + android.net.Uri.encode(it) } ?: ""))).also { it.getJSONArray("data") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (selectedId != id || api !== connection || generation != openingGeneration) return
            if (id in cachedHistory) historyCacheStatus = "离线缓存"
            if (messages.isEmpty()) historyLoading = false
            throw e
        }
        if (id != selectedId || api !== connection || generation != openingGeneration) return
        // Native order and content are authoritative. Local snapshots never contribute rows.
        var history = withConfirmedKeys(id, response.array("data").objects().mapNotNull { row -> agentHistoryMessage(agent, row) })
        historyHasMore = response.optBoolean("has_more", false)
        historyOldestId = response.optString("oldest_id").takeIf { it.isNotBlank() && it != "null" }
        val bindings = mutableListOf<Pair<Long, List<String>>>()
        val confirmedLocalKeys = mutableSetOf<String>()
        // Hermes has no request-key lookup. After an App restart the in-memory
        // pre-send boundary is gone, so reconcile persisted submissions using
        // unambiguous timestamped history rather than leaving them pending forever.
        pendingSubmissions[id]?.takeIf { agent == "codex" && localSubmissions[id] == null }?.let { pending ->
            restoredPendingHistoryMatch(agent, pending, history)?.let { confirmed ->
                rememberMessageKey(id, confirmed.serverId, pending.key)
                history = withConfirmedKeys(id, history)
                markSubmissionAccepted(id, pending.key)
                removePending(id)
                questionAccepted(id)
                if (error.orEmpty().contains("尚未确认")) setError(id, null)
                if (pending.attachmentIds.isNotEmpty()) {
                    bindings += confirmed.serverId to pending.attachmentIds
                }
            }
        }
        val sessionSteering = steering.filter { it.session == id }
        val reconciliation = reconcileSteeringMessages(history, sessionSteering)
        val confirmedSteeringKeys = reconciliation.second.map { it.first.key }.toSet()
        for ((sent, confirmed) in reconciliation.second) {
            val canonical = confirmed.copy(localKey = sent.key)
            rememberMessageKey(id, confirmed.serverId, sent.key)
            history = withConfirmedKeys(id, history)
            narrations = acknowledgeNarrationUser(narrations, id, sent.key, canonical)
            if (sent.attachments.isNotEmpty()) {
                bindings += confirmed.serverId to sent.attachments.map { it.getString("id") }
                history = attachConfirmedSubmission(history, confirmed.serverId, sent.attachments)
            }
        }
        // 放弃的失败记录（>10 分钟）与已确认/滑出窗口的记录一并清除，防止本地气泡永久堆叠在列表尾部
        val abandonedKeys = sessionSteering.filter { isAbandonedSteering(it) }.map { it.key }.toSet()
        if (abandonedKeys.isNotEmpty()) setError(id, "未送达的插话已从本机记录清除；如仍需发送，请重新输入")
        val refreshedKeys = sessionSteering.map { it.key }.toSet()
        steering = steering.filter { it.session != id || it.key !in refreshedKeys } + reconciliation.first.filter { it.key !in abandonedKeys } +
            reconciliation.second.map { it.first }.filter { it.attachments.isNotEmpty() }.map { it.copy(delivery = "已送达") }
        saveSteering()
        localSubmissions[id]?.let { local ->
            val acknowledged = if (agent == "hermes" && pendingSubmissions[id]?.key == local.pending.key) null
                else currentSubmissionHistoryMatch(agent, local.pending, local.existing, history)
            if (acknowledged != null) {
                val canonical = acknowledged.copy(localKey = local.pending.key)
                rememberMessageKey(id, acknowledged.serverId, local.pending.key)
                history = withConfirmedKeys(id, history)
                if (pendingSubmissions[id]?.key == local.pending.key) {
                    markSubmissionAccepted(id, local.pending.key); removePending(id); questionAccepted(id)
                    if (error.orEmpty().contains("尚未确认")) setError(id, null)
                }
                if (local.files.isNotEmpty()) {
                    bindings += acknowledged.serverId to local.files.map { it.getString("id") }
                    history = attachConfirmedSubmission(history, acknowledged.serverId, local.files)
                }
                confirmedLocalKeys += local.pending.key
                narrations = acknowledgeNarrationUser(narrations, id, local.pending.key, canonical)
                runNarrationUsers.entries.forEach { entry ->
                    if (entry.value.localKey == local.pending.key) entry.setValue(canonical)
                }
            }
        }
        // Replace the canonical window, including a successful empty response.
        val activeRun = runId.takeIf { runSession == id } ?: backgroundRuns[id]
        narrations = narrations.filter { it.session != id || it.run == activeRun && activeRun != null }
        saveNarrations()
        compactionNotices = compactionNotices.filterNot { it.session == id }; saveCompactionNotices()
        cachedHistory[id] = history
        historyCacheStatus = null
        val currentSteering = steering.filter { it.session == id && it.key in currentSubmissionKeys && it.key !in confirmedSteeringKeys && !rejectedSteering(it) }
        val pendingRows = steering.filter { it.session == id && it.key !in currentSubmissionKeys && it.key !in confirmedSteeringKeys && !rejectedSteering(it) && it.delivery != "已送达" }.map {
            HermesMessage("user", it.text, attachments = it.attachments, localKey = it.key,
                delivery = it.delivery, timestamp = it.timestamp)
        }.toMutableList()
        var visibleHistory = appendPendingHistory(history, pendingRows, currentSubmissionKeys)
        val usedNativePresentationIds = mutableSetOf<Long>()
        currentSteering.forEach { sent ->
            visibleHistory = projectCurrentSubmission(visibleHistory,
                HermesMessage("user", sent.text, attachments = sent.attachments, localKey = sent.key,
                    delivery = sent.delivery.takeUnless { it == "已送达" }, timestamp = sent.timestamp), sent.existingIds,
                usedNativePresentationIds)
        }
        localSubmissions[id]?.takeUnless { it.pending.key in confirmedLocalKeys }?.let { local ->
            visibleHistory = projectCurrentSubmission(visibleHistory,
                HermesMessage("user", local.pending.input, attachments = local.files,
                    localKey = local.pending.key, delivery = if (hasPendingFor(id)) {
                        if (submitting) "正在发送" else "发送状态待核对"
                    } else null, timestamp = local.pending.timestamp), local.existing, usedNativePresentationIds)
        }
        acknowledgeFinalSync(id, history)
        messages = projectFinalSyncRows(
            reconcilePendingAssistant(withActiveNarrations(visibleHistory, liveNarrations(id), activeRun),
                pendingItemId.takeIf { activeRun != null }, pendingText.takeIf { activeRun != null }.orEmpty()),
            finalSyncStates, id)
        historyLoading = false
        withContext(Dispatchers.IO) { historyCache.write(id, history) }
        if (selectedId != id || api !== connection) return
        reconcileSteeringReceipts(id, connection)
        launch { bindOutgoingIfReady(id, connection) }
        launch {
            for ((messageId, attachmentIds) in bindings) {
                connection.json("$root/sessions/$id/messages/$messageId/attachments", obj("ids" to org.json.JSONArray(attachmentIds)))
                if (api !== connection) return@launch
            }
        // Keep receipts with files until binding succeeds; failed bindings are
        // retried on the next history read without hiding the native image bubble.
        if (localSubmissions[id]?.pending?.key in confirmedLocalKeys) localSubmissions.remove(id)
        if (confirmedSteeringKeys.isNotEmpty()) {
            steering = steering.filterNot { it.session == id && it.key in confirmedSteeringKeys }; saveSteering()
        }
        }
        sessions
            .find { it.optString("id") == id }
            ?.let { title = it.optString("title").ifBlank { "未命名会话" } }
        if (selectedId == id) {
            if (prefetchedFiles?.optJSONArray("data") != null) files = prefetchedFiles.array("data").objects()
            else refreshFiles()
        }
    }

    private suspend fun bindOutgoingIfReady(session: String, connection: NativeApi) {
        var catalog: List<JSONObject>? = null
        for (pending in outgoingFiles.filter { it.session == session }.toList()) {
            if (!bindingOutgoingRuns.add(pending.run)) continue
            try {
            if (!pending.canBind) continue
            var receipt = pending
            if (receipt.ids.isEmpty() && receipt.messageKey.isNotBlank()) {
                val available = catalog ?: connection.json("$root/sessions/$session/files").array("data").objects()
                    .also { catalog = it }
                val ids = available.filter { it.optBoolean("outgoing") &&
                    it.optString("messageKey") == receipt.messageKey }.map { it.getString("id") }
                if (ids.isEmpty()) {
                    if (receipt.discoveryUntil > 0 && System.currentTimeMillis() > receipt.discoveryUntil &&
                        outgoingFiles.remove(pending)) saveOutgoingFiles()
                    continue
                }
                receipt = receipt.copy(ids = ids)
                val index = outgoingFiles.indexOf(pending)
                if (index >= 0) { outgoingFiles[index] = receipt; saveOutgoingFiles() }
            }
            val target = exactFinalAssistant(cachedHistory[session].orEmpty(), receipt.item, receipt.turn,
                receipt.text, receipt.startedAt) ?: continue
            if (target.serverId <= 0) continue
            connection.json("$root/sessions/$session/messages/${target.serverId}/attachments",
                obj("ids" to org.json.JSONArray(receipt.ids)))
            if (outgoingFiles.remove(receipt)) saveOutgoingFiles()
            if (api === connection) {
                val attached = (catalog ?: files).filter { it.optString("id") in receipt.ids }
                cachedHistory[session] = cachedHistory[session].orEmpty().map { row ->
                    if (row.serverId == target.serverId) row.copy(attachments =
                        (row.attachments + attached).distinctBy { it.optString("id") }) else row
                }
                if (selectedId == session) messages = messages.map { row ->
                    if (row.serverId == target.serverId) row.copy(attachments =
                        (row.attachments + attached).distinctBy { it.optString("id") }) else row
                }
            }
            } finally { bindingOutgoingRuns.remove(pending.run) }
        }
    }

    private fun ensureFinalSyncPolling(session: String, connection: NativeApi) {
        if (finalSyncJob?.isActive == true || !needsFinalSyncPolling(session)) return
        finalSyncJob = viewModelScope.launch {
            repeat(12) {
                delay(5000)
                if (selectedId != session || api !== connection || !needsFinalSyncPolling(session)) return@launch
                try {
                    finalSyncStates.filter { it.session == session && (!it.canConfirm || it.needsCompactionCheck()) }.toList().forEach { pending ->
                        try {
                            val status = connection.json("$root/runs/${pending.run}")
                            if (isCompactionRun(pending.run, status)) {
                                discardCompactionReply(pending.run, status)
                                return@forEach
                            }
                            val recovered = status.optString("output").removePrefix(pending.flushedPrefix)
                            if (recovered.isNotBlank()) {
                                keepFinalSync(pending.copy(text = recovered, item = null, canConfirm = true))
                                outgoingFiles.indexOfFirst { it.run == pending.run }.takeIf { it >= 0 }?.let { index ->
                                    outgoingFiles[index] = outgoingFiles[index].copy(text = recovered, item = null, canBind = true)
                                    saveOutgoingFiles()
                                }
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { }
                    }
                    loadHistory(session)
                }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { }
            }
            if (selectedId == session && runId == null && finalSyncStates.any { it.session == session })
                state = "消息同步尚未完成，请刷新"
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

    fun toggleSessionArchive(session: JSONObject) = launch {
        val id = session.optString("id")
        val archived = !session.optBoolean("archived")
        try {
            api.json("$root/sessions/${q(id)}/archive", obj("archived" to archived))
            sessions = sessions.map { if (it.optString("id") == id) JSONObject(it.toString()).put("archived", archived) else it }
            saveOfflineSessions()
        } catch (e: Exception) {
            error = "存档失败：${e.message ?: "未知错误"}"
        }
    }

    fun moveSession(id: String, projectId: String, onDone: () -> Unit) = launch {
        try {
            val result = api.json("$root/sessions/${q(id)}/move", obj("projectId" to projectId))
            val row = result.getJSONObject("session")
            sessions = sessions.map { if (it.optString("id") == id) row else it }
            saveOfflineSessions()
        } catch (e: Exception) {
            error = "移动会话失败：${e.message ?: "未知错误"}"
        }
        onDone()
    }

    fun renameSession(id: String, name: String, onDone: () -> Unit) = launch {
        val body = obj("title" to name.trim()).toString().toRequestBody("application/json".toMediaType())
        val result = api.json(api.request("$root/sessions/$id").newBuilder().patch(body).build())
        val renamed = result.getJSONObject("session").optString("title")
        sessions = sessions.map { if (it.optString("id") == id) JSONObject(it.toString()).put("title", renamed) else it }
        saveOfflineSessions()
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
        saveOfflineSessions()
        cachedHistory.remove(id)
        if (finalSyncStates.removeAll { it.session == id }) saveFinalSyncStates()
        if (outgoingFiles.removeAll { it.session == id }) saveOutgoingFiles()
        withContext(Dispatchers.IO) { historyCache.remove(id) }
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
            { session -> queueMayDispatch(
                runId != null && runSession == session,
                conversationUi.entry(session).submitting, hasPendingFor(session), false, false) },
            { if (agent == "codex") currentCodexAccountId() else "hermes" },
            { row, result -> dispatchQueuedMessage(row, result) })
    }
    fun enqueueDraft(): Boolean {
        if (submitting || uploading || selectedId == null) return false
        if (draft.trim().startsWith("/")) { error = "斜杠命令请直接执行，消息队列用于对话内容"; return false }
        val files = pendingFiles.toList()
        if (!messageQueue.enqueue(draft.trim(), files)) {
            if (!recoverState()) error = "账号信息尚未就绪，请稍后再试；草稿已保留"
            return false
        }
        draft = ""; pendingFiles = emptyList()
        syncWatchService()
        return true
    }
    fun pollMessageQueue() = viewModelScope.launch {
        for (session in messageQueue.entries.map { it.session }.distinct()) {
            if (agent == "codex" && messageQueue.entries.any { it.session == session && it.key == pendingSubmissions[session]?.key })
                try { reconcilePendingNow(session) } catch (e: CancellationException) { throw e } catch (_: Exception) { }
            messageQueue.tick(session)
        }
    }
    private fun dispatchQueuedMessage(row: QueuedChatMessage, response: JSONObject?) {
        if (conversationUi.entry(row.session).submitting || hasPendingFor(row.session)) return
        val pending = PendingAgentSubmission(row.key, row.text, row.files.map { it.getString("id") }, System.currentTimeMillis(), modelSettings = submissionModelSettings(row.session))
        currentSubmissionKeys += row.key
        if (response == null) {
            pendingSubmissions = pendingSubmissions + (row.session to pending); savePending()
            submit(pending, row.session, row.files, clearDraft = false)
        } else {
            localSubmissions[row.session] = LocalSubmission(pending, messages.map { it.serverId }.toSet(), row.files)
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

    private fun sendInput(input: String, questionId: String? = null, hermesExternalVerified: Boolean = false): Boolean {
        val session = selectedId ?: return false
        if (messageActionBusy) { error = "请等待消息操作完成"; return false }
        if (hasPendingSubmission) { error = "请先核对上一次提交结果，避免重复创建任务"; return false }
        if (input.isBlank() || submitting) return false
        if (externalRunning && agent == "codex") {
            val connection = api
            val originalDraft = draft
            val originalFiles = pendingFiles.map { it.optString("id") }
            setSubmitting(session, true)
            viewModelScope.launch {
                var dispatched = false
                try {
                    retryAgentRead { withTimeout(8_000) { attachServerActiveRun(session, allowSubmitting = true, required = true) } }
                    if (selectedId != session || api !== connection) return@launch
                    if (runId == null) {
                        val current = retryAgentRead { withTimeout(8_000) {
                            connection.json("$root/sessions/${q(session)}/activity").also {
                                if (!it.optBoolean("available")) throw IOException("暂时无法核对任务归属")
                            }
                        } }
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
        // Hermes external runs: the desktop holds the turn lease, so a normal submission is
        // safely queued by the gateway instead of racing the running turn. Steer stays the
        // fast path when the run registry still exposes the active run.
        if (agent == "hermes" && externalRunning && runId == null && !isQuestion && !hermesExternalVerified) {
            val connection = api
            val originalDraft = draft
            val originalFiles = pendingFiles.map { it.optString("id") }
            setSubmitting(session, true)
            viewModelScope.launch {
                var dispatched = false
                try {
                    retryAgentRead { withTimeout(8_000) { attachServerActiveRun(session, allowSubmitting = true, required = true) } }
                    if (selectedId != session || api !== connection) return@launch
                    if (draft == originalDraft && pendingFiles.map { it.optString("id") } == originalFiles) {
                        setSubmitting(session, false)
                        dispatched = sendInput(input, questionId, hermesExternalVerified = true)
                    }
                } catch (_: kotlinx.coroutines.TimeoutCancellationException) { setError(session, "核对任务归属超时，文字和附件已保留，请重新连接")
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { setError(session, "无法核对任务归属，文字和附件已保留，请重新连接") }
                finally { if (!dispatched) setSubmitting(session, false) }
            }
            return true
        }
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
            currentSubmissionKeys += steerKey
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
                    steeringRunPreflightFailure(agent, live.optString("status"))?.let { throw it }
                    val request = connection.request("$root/runs/$id/steer", obj("input" to input, "session_id" to session, "attachment_ids" to org.json.JSONArray(attached.map { it.getString("id") }))).newBuilder().header("Idempotency-Key", steerKey).build()
                    connection.json(request)
                    accepted = true
                    questionAccepted(session); updateSteering(steerKey, "已送达")
                    if (selectedId == session) messages = messages.map { if (it.localKey == steerKey) it.copy(delivery = null) else it }
                    if (selectedId == session && runId == id && api === connection)
                        messages.firstOrNull { it.role == "user" && it.localKey == steerKey }?.let { runNarrationUsers[id] = it }
                    val ids = attached.map { it.getString("id") }.toSet()
                    draftFiles = draftFiles + (session to draftFiles[session].orEmpty().filterNot { it.optString("id") in ids })
                    if (selectedId == session && api === connection) loadHistory(session)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (accepted) {
                        if (!recoverState(session)) setError(session, "插话已送达，历史同步暂未恢复")
                        return@launch
                    }
                    setError(session, if (writeWasRejected(e)) connectionFailureMessage(e)
                        else "插话发送结果尚未确认，请核对会话历史后再决定是否重发。${connectionFailureMessage(e)}")
                    updateSteering(steerKey, steeringFailureDelivery(e))
                    if (writeWasRejected(e)) {
                        steering = steering.filterNot { it.key == steerKey }; saveSteering()
                        if (selectedId == session) { messages = messages.filterNot { it.localKey == steerKey } }
                    }
                    if (staleRunFailure(e)) clearRunTracking(session, id)
                    if (!isQuestion && conversationUi.entry(session).draft.isBlank()) setDraft(session, input)
                    if (isQuestion) prefs.edit().remove("answeringQuestion:$session").apply()
                    if (canRecoverAgentRead(e) && recoverState(session)) return@launch
                    if (selectedId == session && api === connection) try { loadHistory(session); loadSelection(session) }
                    catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                } finally { setSubmitting(session, false) }
            }
            return true
        }
        val pending = PendingAgentSubmission(UUID.randomUUID().toString(), input,
            if (isQuestion) emptyList() else pendingFiles.map { it.getString("id") }, System.currentTimeMillis(), questionId, submissionModelSettings(session))
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
            if (it.role == "user" && it.localKey == key) it.copy(delivery = null) else it
        }
        cachedHistory[session]?.let { rows -> cachedHistory[session] = rows.map {
            if (it.role == "user" && it.localKey == key) it.copy(delivery = "已送达") else it
        } }
    }

    private fun submit(pending: PendingAgentSubmission, session: String, queuedFiles: List<JSONObject>? = null, clearDraft: Boolean = true) {
        val key = pending.key; val input = pending.input
        currentSubmissionKeys += key
        // 壳值 "custom"（billing 占位）不下发：下发反而让网关解析到错误提供方；
        // 留空时服务端会用会话锁或全局默认补齐（与 /runs 注入跳过壳值同一语义）。
        val provider = sessionProvider.takeIf { selectedId == session && it.isNotBlank() && it != "custom" }.orEmpty()
        val model = sessionModel.takeIf { selectedId == session }.orEmpty()
        val attached = queuedFiles ?: pendingFiles.filter { it.optString("id") in pending.attachmentIds }
        setSubmitting(session, true); setError(session, null)
        if (localSubmissions[session]?.pending?.key != key) {
            localSubmissions[session] = LocalSubmission(pending,
                (if (selectedId == session) messages else cachedHistory[session].orEmpty()).map { it.serverId }.toSet(), attached)
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
                    "account_id" to currentCodexAccountId(),
                    "attachment_ids" to org.json.JSONArray(pending.attachmentIds))
                if (agent == "codex" && pending.questionId != null) payload.put("question_reply_id", pending.questionId)
                if (agent == "codex") pending.modelSettings?.let { payload.put("model_settings", JSONObject(it)) }
                if (agent == "hermes") {
                    provider.takeIf { it.isNotBlank() }?.let { payload.put("provider", it) }
                    model.takeIf { it.isNotBlank() }?.let { payload.put("model", it) }
                }
                val request = api.request("$root/runs", payload).newBuilder().header("Idempotency-Key", key).build()
                val response = api.json(request)
                val startedRun = response.getString("run_id")
                when (response.optString("delivery")) {
                    "rejected" -> throw ApiRequestFailure("本次消息未写入", 409, "run_rejected", "rejected")
                    "unknown" -> throw ApiRequestFailure("消息是否写入尚未确认，请核对会话历史", 409, "delivery_unknown", "unknown")
                }
                when (response.optString("status")) {
                    "acceptance_unknown" -> throw ApiRequestFailure("上次发送结果尚未确认，请核对会话历史；不会重复发送", 409, "delivery_unknown", "unknown")
                    "failed", "interrupted", "cancelled", "completed" -> {
                        accepted = true; markSubmissionAccepted(session, key); removePending(session); questionAccepted(session)
                        if (selectedId == session) loadHistory(session)
                        return@launch
                    }
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
                    // Older completed runs keep their own final text and attachment receipts.
                    events = emptyList(); eventCount = 0; approval = null; seq = -1; state = "执行中"
                    runVerifiedAt = activityClock(); activityNow = runVerifiedAt
                    watch()
                    // hermes 网关写消息是异步的，立刻 loadHistory 可能还没入库导致确认失败。
                    // 延迟 1.5s 再拉，给网关写入时间；后续 SSE/轮询的 loadHistory 也会重试确认。
                    launch { kotlinx.coroutines.delay(1_500); loadHistory(session) }
                } else backgroundRuns[session] = startedRun
                saveRuns()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (accepted) {
                    if (!recoverState(session)) setError(session, "消息已送达，历史同步暂未恢复")
                    return@launch
                }
                if (agent == "codex" && e is ApiRequestFailure && e.code == "account_changed") {
                    // Refresh the identity, but never replay a write automatically under
                    // a different account. The rejection path below restores the draft.
                    try { fetchAccountsNow() }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { }
                }
                messageQueue.failed(key, writeWasRejected(e))
                if (writeWasRejected(e)) {
                    removePending(session)
                    prefs.edit().remove("answeringQuestion:$session").apply()
                    if (clearDraft && pending.questionId == null && conversationUi.entry(session).draft.isBlank()) setDraft(session, input)
                    localSubmissions.remove(session)
                    if (selectedId == session) {
                        messages = messages.filterNot { it.localKey == key }
                        pollContext()
                    }
                } else {
                    if (selectedId == session) messages = messages.map { if (it.localKey == key) it.copy(delivery = "发送状态待核对") else it }
                    try { reconcilePendingNow(session) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                    if (!hasPendingFor(session)) return@launch
                }
                if ((canRecoverAgentRead(e) || !writeWasRejected(e)) && recoverState(session)) return@launch
                setError(session, if (writeWasRejected(e)) connectionFailureMessage(e)
                    else "消息发送结果尚未确认，请核对会话历史；不要重复发送。${connectionFailureMessage(e)}")
            } finally { setSubmitting(session, false) }
        }
    }

    /** A live run receipt can outlast its SSE transport. Rebuild progress from durable tool rows. */
    private suspend fun refreshRunActivity(id: String, session: String, connection: NativeApi) {
        if (agent != "hermes" || (streamConnected && events.isNotEmpty() && !streamGap) || executionCompacting) return
        val now = activityClock()
        if (runActivityAttempt?.let { it.first == id && now - it.second < 5_000L } == true) return
        runActivityAttempt = id to now
        try {
            val snapshot = withTimeout(5_000L) { connection.json("$root/sessions/${q(session)}/activity") }
            if (runId != id || runSession != session || selectedId != session || api !== connection) return
            if (snapshot.optString("session_id") != session || !snapshot.optBoolean("available") || !snapshot.optBoolean("running")) return
            val started = parseMessageTimestamp(snapshot.opt("started_at"))
            // Do not hydrate progress from a preceding turn whose lease has not yet cleared.
            if (started != null && currentRunTiming.startedAt?.let { started < it - 5_000L } == true) return
            runActivitySnapshot = id to snapshot
            runActivityObservedAt = activityClock()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Keep SSE/status observation independent of this optional read. */ }
    }

    private fun watch() {
        val epoch = ++observationEpoch
        watching?.cancel()
        streamJob?.cancel()
        streamConnected = false
        statusRecovering = false; streamRecovering = false
        if (!observation.active || !this::api.isInitialized) return
        val id = runId ?: return
        if (streamGapRun != id) { streamGapRun = id; streamGap = false; finalSnapshotReceived = false }
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
                    } catch (e: RunEventsGone) {
                        // Gateway dropped the event transport for a still-running run (or it is
                        // already terminal). Stop hammering /events; the status poller below stays
                        // authoritative and loadHistory() keeps the message list current.
                        if (streamGapRun == id) streamGap = true
                        watchedSession.let { launch { loadHistory(it) } }
                        streamRecovering = false
                        wake.trySend(Unit)
                        break
                    } catch (_: Exception) {
                        /* Status polling remains authoritative; reconnect only this GET stream. */
                    }
                    if (runId != id) break
                    if (activityClock() - started >= 15_000) reconnects = 0
                    reconnects++
                    if (epoch == observationEpoch) streamRecovering = reconnects < 4
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
                    statusRecovering = false
                    if (error == readConnectionError) error = null
                    readConnectionError = null
                    noteRunActivity(watchedSession, result, requestedAt)
                    runVerifiedAt = requestedAt
                    runTurnId = result.optString("turn_id").takeIf { it.isNotBlank() && it != "null" }
                    startRunTiming(id, result)
                    state = if (stoppingRunId == id && result.optString("status") in setOf("started", "submitting", "running", "queued", "stopping", "waiting_for_approval")) "正在停止"
                        else if (result.optString("kind") == "compact" && result.optString("status") == "started") "正在压缩上下文" else statusLabel(result.optString("status"))
                    approval = result.optJSONObject("approval")
                    if (result.optString("status") in setOf("started", "running", "stopping", "waiting_for_approval")) {
                        refreshRunActivity(id, watchedSession, connection)
                        if (runId != id || api !== connection) return@launch
                    }
                    result.optJSONObject("runtime")?.let { rt ->
                        val sseProvider = rt.optString("provider")
                        // Codex reports the actual routing provider, including custom and
                        // official aliases. Hermes may report an unresolved billing shell.
                        val sseProviderKnown = sseProvider.isNotBlank() && (agent == "codex" ||
                            sseProvider != "custom" && modelOptions.array("providers").objects().any { providerMatches(it, sseProvider) })
                        if (sseProviderKnown) {
                            runtime = listOf(providerDisplayName(sseProvider), rt.optString("model")).filter { piece -> piece.isNotBlank() }.joinToString(" · ")
                            if (selectedId == runSession) { sessionProvider = sseProvider; sessionModel = rt.optString("model") }
                        } else if (sessionModel.isBlank()) {
                            // Without a provider report, retain the confirmed selection.
                            sessionModel = rt.optString("model")
                            updateRuntimeDisplay()
                        }
                    }
                    if (
                        result.optString("status") in
                            setOf("completed", "failed", "cancelled", "interrupted", "acceptance_unknown")
                    ) {
                        val compact = isCompactionRun(id, result)
                        if (compact) discardCompactionReply(id, result)
                        runSession?.let { recordCompactionCompletion(id, it, result) }
                        if (result.optString("status") != "completed")
                            error = result.optString("error").ifBlank { state }
                        val statusText = if (compact || result.optString("status") == "acceptance_unknown") "" else result.optString("output")
                        val terminalText = if (compact) "" else terminalSyncText(pendingText, pendingPhase, streamGap, statusText, flushedStreamPrefix)
                        val statusTail = statusText.removePrefix(flushedStreamPrefix)
                        val canConfirm = terminalText.isNotBlank() &&
                            (statusTail.isNotBlank() && terminalText.trim() == statusTail.trim() || finalSnapshotReceived)
                        val terminalItem = pendingItemId.takeIf { pendingPhase == "final_answer" &&
                            (!streamGap || terminalText == pendingText) }
                        val completedMessageKey = prefs.getString("runMessageKey", "").orEmpty()
                        val returned = files.filter { it.optBoolean("outgoing") &&
                            completedMessageKey.isNotBlank() && it.optString("messageKey") == completedMessageKey }
                        // Keep the live item and the run receipt until a successful
                        // authoritative read has taken over. A failed read must retry
                        // without hiding the final streamed text.
                        val finishedSession = runSession
                        val finalSync = FinalSyncState(watchedSession, id, terminalItem, runTurnId, terminalText,
                            pendingTextTimestamp ?: System.currentTimeMillis(), runTimings[id]?.startedAt, canConfirm,
                            flushedStreamPrefix)
                        if (!compact && finishedSession != null && completedMessageKey.isNotBlank()) {
                            outgoingFiles.removeAll { it.run == id }
                            outgoingFiles.add(OutgoingFiles(finishedSession, id, finalSync.item, finalSync.turn,
                                finalSync.text, finalSync.startedAt, returned.map { it.getString("id") },
                                completedMessageKey, System.currentTimeMillis() + 300_000L, canConfirm))
                            saveOutgoingFiles()
                        }
                        finishedSession?.let { loadHistory(it) }
                        val historyReady = compact || finishedSession == null ||
                            finalSyncAcknowledged(finalSync, cachedHistory[finishedSession].orEmpty())
                        if (finishedSession != null && !historyReady) keepFinalSync(finalSync)
                        runId = null
                        runVerifiedAt = 0
                        discardRunNarrations(id)
                        runNarrationUsers.remove(id)
                        backgroundRuns.values.remove(id); saveRuns()
                        approval = null
                        streamJob?.cancel()
                        streamRecovering = false
                        prefs.edit().remove("run").remove("runSession").apply()
                        if (finishedSession != null && selectedId == finishedSession) {
                            messages = projectFinalSyncRows(cachedHistory[finishedSession].orEmpty(), finalSyncStates, finishedSession)
                            pendingText = ""; pendingItemId = null; pendingPhase = ""; streamGap = false
                            if (!historyReady) {
                                state = "已结束，正在同步消息"
                            }
                            ensureFinalSyncPolling(finishedSession, connection)
                        }
                        currentCoroutineContext().ensureActive()
                        refreshSessions()
                        break
                    }
                    failures = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    currentCoroutineContext().ensureActive()
                    failures++
                    // run 在服务端已不存在（404 run_not_found）：视为任务已终结，清除跟踪而不是无限重试刷错误。
                    if (e is ApiRequestFailure && e.status == 404) {
                        noteRunActivity(watchedSession, obj("status" to "missing"), activityClock())
                        runId = null; approval = null
                        discardRunNarrations(id)
                        runNarrationUsers.remove(id)
                        streamJob?.cancel()
                        streamRecovering = false; statusRecovering = false
                        backgroundRuns.values.remove(id); saveRuns()
                        prefs.edit().remove("run").remove("runSession").apply()
                        state = "正在同步会话"
                        recoverState(watchedSession)
                        break
                    }
                    statusRecovering = failures < 4 && canRecoverAgentRead(e)
                    if (!statusRecovering && !stateRecovering) {
                        error = "多次读取任务状态失败：${connectionFailureMessage(e)}"
                        readConnectionError = error
                    }
                }
                withTimeoutOrNull(agentStatusPollDelay(streamConnected, failures)) { wake.receive() }
            }
        }
    }

    fun reconnect() {
        recoverState(manual = true)
    }

    private suspend fun readEvents(connection: NativeApi, id: String, epoch: Long) {
        val request =
            connection.request("$root/runs/$id/events")
                .newBuilder()
                .apply { if (seq >= 0) header("Last-Event-ID", seq.toString()) }
                .build()
        try { connection.streamingResponse(request) { response ->
            if (!response.isSuccessful) {
                // 404 = the gateway holds no event transport for this run (swept mid-run on
                // long silent turns, or the run is already terminal). Retrying can never
                // succeed; surface it so the reconnect loop degrades to history sync.
                if (response.code == 404) throw RunEventsGone()
                return@streamingResponse
            }
            if (runId != id || api !== connection || !observation.active || epoch != observationEpoch) return@streamingResponse
            streamConnected = true
            streamRecovering = false
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
                                val kind = event.optString("type", event.optString("event"))
                                if (kind == "stream.gap" || n >= 0 && missingSseEvents(seq, n)) {
                                    streamGap = true
                                    runSession?.let { session -> launch { loadHistory(session) } }
                                    state = "正在同步遗漏的输出"
                                }
                                if (n >= 0) seq = n
                                if (kind == "stream.gap") return@withContext
                                recordRunResponse(id, event)
                                compactionStreamPhase(agent, event)?.let { started ->
                                    if (started) {
                                        if (streamCompaction?.first != id) streamCompaction = id to
                                            (parseMessageTimestamp(event.opt("timestamp")) ?: System.currentTimeMillis())
                                    } else {
                                        if (streamCompaction?.first == id) streamCompaction = null
                                        // Read the native item at its native position; never persist a synthetic notice.
                                        val session = runSession
                                        if (session != null) launch { loadHistory(session); pollContext() }
                                    }
                                    return@withContext
                                }
                                if (event.optString("type", event.optString("event")) in setOf("approval.request", "run.completed", "run.failed", "run.cancelled", "run.interrupted")) statusWake?.trySend(Unit)
                                when (event.optString("type", event.optString("event"))) {
                                    "message.started" -> beginStreamItem(id, event, n.takeIf { it >= 0 })
                                    "message.delta" -> {
                                         if (streamGap) return@withContext
                                        beginStreamItem(id, event, n.takeIf { it >= 0 })
                                        if (pendingTextTimestamp == null) pendingTextTimestamp = parseMessageTimestamp(event.opt("timestamp")) ?: System.currentTimeMillis()
                                        pendingText += event.optString("delta")
                                        syncPendingCanonical()
                                    }
                                    "message.snapshot", "message.completed" -> {
                                         streamGap = false
                                        beginStreamItem(id, event, n.takeIf { it >= 0 })
                                        if (pendingTextTimestamp == null) pendingTextTimestamp = parseMessageTimestamp(event.opt("timestamp")) ?: System.currentTimeMillis()
                                        pendingText = event.optString("text")
                                        if (kind == "message.completed" && pendingPhase == "final_answer") finalSnapshotReceived = true
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
                                                            .let { if (agent == "codex") compactCodexToolPreview(it) else it }
                                                            .replace(Regex("\\s+"), " ")
                                                            .take(100),
                                                         event.optString("item_id").ifBlank { event.optString("call_id") }.takeIf { it.isNotBlank() },
                                                    ))
                                                .takeLast(20)
                                        eventCount++
                                    }
                                    "tool.completed",
                                    "tool.failed" -> {
                                        events = completeToolEvent(events, event.optString("tool"),
                                            event.optString("item_id").ifBlank { event.optString("call_id") }.takeIf { it.isNotBlank() },
                                            event.optBoolean("error") || event.optString("event") == "tool.failed")
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

    var stopRequestInFlight by mutableStateOf(false)
        private set
    private var stoppingRunId by mutableStateOf<String?>(null)
    val stopping get() = stopRequestInFlight || runId != null && (stoppingRunId == runId || state == "正在停止")

    fun stop() = launch {
        val session = selectedId ?: return@launch
        val connection = api
        if (stopRequestInFlight) return@launch
        stopRequestInFlight = true
        setError(session, null)
        try {
            // Desktop/API turns may not be in the phone's saved run registry yet.
            if (runId == null) attachServerActiveRun(session, required = true)
            if (selectedId != session || api !== connection) return@launch
            val id = runId ?: throw java.io.IOException(if (agent == "codex" && canTakeover)
                "当前任务由桌面端管理，请使用“中断并接管”。" else if (externalRunning)
                "未找到可控制的任务，请在运行该会话的原客户端停止。" else "当前会话没有正在运行的任务，请刷新会话。")
            if (runSession != session) throw java.io.IOException("当前任务不属于所选会话，请刷新后重试。")
            // Prevent a stopped turn from being immediately followed by the phone's next queued message.
            if (agent == "hermes" && messageQueue.visible.isNotEmpty()) messageQueue.pause(true)
            val result = connection.json("$root/runs/$id/stop", obj())
            if (runId != id || selectedId != session || api !== connection) return@launch
            val status = result.optString("status")
            if (status in setOf("completed", "failed", "cancelled", "interrupted") ||
                result.optBoolean("stopped") && result.has("stop_requested")) {
                clearRunTracking(session, id); stoppingRunId = null
                state = if (status.isNotBlank()) statusLabel(status) else "任务已结束"
                loadHistory(session); loadSelection(session)
            } else if (status == "stopping" || result.optBoolean("stop_requested") || result.optBoolean("stopped")) {
                stoppingRunId = id
                state = "正在停止"
                statusWake?.trySend(Unit)
            } else throw java.io.IOException("服务端尚未确认停止请求，请刷新任务状态后重试。")
        } finally {
            stopRequestInFlight = false
        }
    }

    /** 放弃手机端写入权：有 run 先中断，再释放 writer 锁（空闲会话同样适用）。 */
    fun handoverSession(sessionId: String) = launch {
        val connection = api
        if (stopRequestInFlight) return@launch
        stopRequestInFlight = true
        setError(sessionId, null)
        try {
            val id = if (runSession == sessionId) runId else backgroundRuns[sessionId]
            val result = if (id != null)
                connection.json("$root/runs/$id/handover", obj())
            else
                connection.json("$root/sessions/${q(sessionId)}/handover", obj("action" to "handover"))
            if (api !== connection) return@launch
            if (id != null) clearRunTracking(sessionId, id)
            stoppingRunId = null
            if (selectedId == sessionId) {
                sessionControl = null; sessionControlVerifiedAt = 0L
                state = if (result.optBoolean("interrupted")) "任务已中断，写入权已释放" else "写入权已释放"
            }
            loadHistory(sessionId); loadSelection(sessionId)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            setError(sessionId, "转交失败：${connectionFailureMessage(e)}")
        } finally { stopRequestInFlight = false }
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

    /** slug → 用户可读的 provider 显示名：优先 modelOptions 目录里的 name，回落去前缀的 slug。 */
    private fun providerDisplayName(slug: String): String {
        if (slug.isBlank() || agent == "hermes" && slug == "custom") return ""
        if (this::api.isInitialized) {
            modelOptions.array("providers").objects()
                .firstOrNull { providerMatches(it, slug) }
                ?.optString("name")?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return if (agent == "codex" && slug == "custom") slug else displayProvider(slug)
    }

    private fun effectiveSelection() = effectiveAgentSelection(agent,
        AgentSelection(sessionProvider, sessionModel, sessionEffort, sessionTier), modelOptions)

    private fun submissionModelSettings(session: String): String? {
        if (agent != "codex") return null
        val selection = if (selectedId == session) effectiveSelection() else {
            val saved = cachedSessionRuntime(session)
            effectiveAgentSelection(agent, AgentSelection(saved?.cleanSetting("provider").orEmpty(),
                saved?.cleanSetting("model").orEmpty(), saved?.cleanSetting("effort").orEmpty(), saved?.cleanSetting("tier").orEmpty()), modelOptions)
        }
        return codexModelSettingsSnapshot(selection)
    }

    /** Header and submission share one effective selection, including actual custom providers. */
    private fun updateRuntimeDisplay() {
        val selection = effectiveSelection()
        runtime = listOf(providerDisplayName(selection.provider), selection.model).filter { it.isNotBlank() }.joinToString(" · ")
    }

    fun fetchModels() = launch {
        modelOptions = api.json("$root/model-options")
        updateRuntimeDisplay()
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

    private val acceptedShares = mutableSetOf<String>()
    fun acceptSharedDraft(shared: SharedChatDraft): Boolean {
        if (shared.id in acceptedShares) return true
        val session = shared.session.getString("id")
        val combined = (draftFiles[session].orEmpty() + shared.attachments).distinctBy { it.optString("id") }
        if (uploading || combined.size > 8) return false
        select(shared.session)
        draftFiles = draftFiles + (session to combined)
        if (shared.text.isNotBlank()) draft = listOf(draft, shared.text).filter { it.isNotBlank() }.joinToString("\n")
        acceptedShares += shared.id
        return true
    }

    fun removePendingFile(id: String) {
        pendingFiles = pendingFiles.filter { it.optString("id") != id }
    }

    fun upload(uri: android.net.Uri, image: Boolean, onFinished: () -> Unit = {}) =
        upload(listOf(uri), image, onFinished)

    fun upload(uris: List<android.net.Uri>, image: Boolean, onFinished: () -> Unit = {}) {
        val session = selectedId ?: run { onFinished(); return }
        val connection = api
        if (uploading || uris.isEmpty()) { onFinished(); return }
        val available = (8 - pendingFiles.size).coerceAtLeast(0)
        if (available == 0) { setError(session, "每条消息最多 8 个附件"); onFinished(); return }
        val selected = uris.distinct()
        if (selected.size > available) setError(session, "每条消息最多 8 个附件，本次只添加前 $available 个")
        uploading = true
        launch {
            try {
                for (uri in selected.take(available)) {
                    try {
                        val file = withContext(Dispatchers.IO) { prepareHermesUpload(getApplication(), uri, image) }
                        try {
                            val multipart = okhttp3.MultipartBody.Builder()
                                .setType(okhttp3.MultipartBody.FORM)
                                .addFormDataPart("file", file.name, file.body)
                                .build()
                            val request = connection.request("$root/sessions/$session/files")
                                .newBuilder().post(multipart).build()
                            val result = connection.json(request)
                            draftFiles = draftFiles + (session to (draftFiles[session].orEmpty() + result))
                        } finally {
                            file.temporary.delete()
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { setError(session, e.message ?: "附件上传失败") }
                }
            } finally {
                uploading = false
                onFinished()
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
        if (agent == "codex") {
            val metadata = modelOptions.array("providers").objects().firstOrNull { providerMatches(it, provider) }
            if (supportsServiceTier(metadata)) body.put("service_tier", tier)
        } else if (!global) body.put("model_options", obj("reasoning" to hermesReasoning(effort)))
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
                runtime = listOf(providerDisplayName(provider), model).filter { it.isNotBlank() }.joinToString(" · ")
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

// Thrown when the runs/{id}/events SSE endpoint answers 404: the gateway holds no event
// transport for this run (mid-run sweep on long silent turns, or the run is terminal).
// The reconnect loop treats it as permanent and degrades to history sync instead of
// retrying a request that can never succeed.
private class RunEventsGone : IOException("run events stream gone (404)")

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
