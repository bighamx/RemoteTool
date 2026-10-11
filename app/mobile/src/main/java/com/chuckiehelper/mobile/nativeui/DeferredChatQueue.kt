package com.chuckiehelper.mobile.nativeui

import android.content.SharedPreferences
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class QueuedChatMessage(val key: String, val session: String, val text: String,
    val files: List<JSONObject>, val nativeId: String? = null, val mode: String = "", val status: String = "等待入队",
    val accountId: String? = null)

internal fun queueMayDispatch(running: Boolean, submitting: Boolean, uncertain: Boolean, editing: Boolean, paused: Boolean) =
    !running && !submitting && !uncertain && !editing && !paused

internal fun restoreQueuedMessages(json: String): List<QueuedChatMessage> = runCatching {
    JSONArray(json).objects().map { row -> QueuedChatMessage(row.getString("key"),row.getString("session"),row.optString("text"),
        row.array("files").objects(),row.optString("nativeId").takeIf { it.isNotBlank() && it != "null" },row.optString("mode"),
        if(row.optString("status")=="发送中") "发送结果待核对" else row.optString("status","等待入队"),
        row.optString("accountId").takeIf { it.isNotBlank() && it != "null" }) }
}.getOrDefault(emptyList())

internal fun queuedMessagesJson(rows: List<QueuedChatMessage>): String = JSONArray(rows.map { row ->
    obj("key" to row.key,"session" to row.session,"text" to row.text,"files" to JSONArray(row.files),
        "nativeId" to row.nativeId,"mode" to row.mode,"status" to row.status,"accountId" to row.accountId)
}).toString()

internal class DeferredChatQueue(
    private val prefs: SharedPreferences, private val scope: CoroutineScope, private val agent: String,
    private val api: () -> NativeApi, private val session: () -> String?,
    private val ready: (String) -> Boolean, private val identity: () -> String?,
    private val dispatch: (QueuedChatMessage, JSONObject?) -> Unit,
) {
    var entries by mutableStateOf(restoreQueuedMessages(prefs.getString("message_queue", "[]") ?: "[]"))
        private set
    var busy by mutableStateOf(false); private set
    var panelOpen by mutableStateOf(false)
    var paused by mutableStateOf(prefs.getBoolean("queue_paused",false)); private set
    val visible get() = entries.filter { it.session == session() }
    private fun save() { prefs.edit().putString("message_queue",queuedMessagesJson(entries)).apply() }
    private fun update(key: String, change: (QueuedChatMessage)->QueuedChatMessage) { entries=entries.map { if(it.key==key)change(it) else it };save() }
    fun pause(value: Boolean) { paused=value;prefs.edit().putBoolean("queue_paused",value).apply() }
    fun accepted(key: String) { entries=entries.filterNot { it.key==key };save() }
    fun failed(key: String, rejected: Boolean) { update(key) { it.copy(status=if(rejected) "发送失败，已暂停" else "发送结果待核对") } }
    fun enqueue(text: String, files: List<JSONObject>): Boolean {
        val id=session() ?: return false
        if (text.isBlank() && files.isEmpty())return false
        val account=identity()?.takeIf { it.isNotBlank() } ?: return false
        val row=QueuedChatMessage(UUID.randomUUID().toString(),id,text.ifBlank { "请查看附件" },files,accountId=account)
        entries=entries+row;save()
        return true
    }
    private fun body(row: QueuedChatMessage, action: String) = obj("action" to action,"key" to row.key,"id" to row.nativeId,
        "session_id" to row.session,"input" to row.text,"attachment_ids" to JSONArray(row.files.map { it.getString("id") }))
    private suspend fun native(row: QueuedChatMessage, action: String) = api().json("/api/codex/sessions/${q(row.session)}/queue",body(row,action))
    fun edit(row: QueuedChatMessage, text: String, files: List<JSONObject>) {
        if(busy || row.status.contains("待核对") || row.accountId != identity() || text.isBlank() && files.isEmpty())return
        busy=true
        scope.launch {
            try {
                val changed=row.copy(text=text.ifBlank { "请查看附件" },files=files)
                if(row.nativeId!=null)native(changed,"update")
                update(row.key) { changed.copy(key=if(row.status.startsWith("发送失败") || row.status.startsWith("入队失败")) UUID.randomUUID().toString() else row.key,status="等待任务结束") }
            } catch(e: CancellationException){throw e}
            catch(e: Exception){update(row.key){it.copy(status="修改失败：${connectionFailureMessage(e)}")}}
            finally{busy=false}
        }
    }
    fun remove(row: QueuedChatMessage) {
        if(busy)return
        busy=true
        scope.launch {
            try {
                var resolved=row
                if(agent=="codex" && row.nativeId==null && row.status=="入队结果待核对") {
                    val catalog=api().json("/api/codex/sessions/${q(row.session)}/queue")
                    if(catalog.optString("mode")!="native")throw IllegalStateException("暂时无法核对原生队列")
                    val found=catalog.array("data").objects().firstOrNull{it.optString("clientUserMessageId")==row.key}
                    resolved=row.copy(nativeId=found?.getString("id"))
                }
                if(resolved.nativeId!=null)native(resolved,"delete")
                accepted(row.key)
            }
            catch(e: CancellationException){throw e}
            catch(e: Exception){update(row.key){it.copy(status="移除失败：${connectionFailureMessage(e)}")}}
            finally{busy=false}
        }
    }
    fun forgetUncertainNative(row: QueuedChatMessage) {
        if (busy || row.mode != "native" || !row.status.contains("待核对") && !row.status.contains("待恢复") && !row.status.contains("所属账号")) return
        accepted(row.key)
    }
    suspend fun tick(targetSession: String) {
        if(busy)return
        val current=entries.filter { it.session == targetSession }
        if(current.isEmpty())return
        busy=true
        try {
                val originalSession=targetSession
                var catalog: JSONObject?=null
                if(agent=="codex" && current.any { it.mode != "local" })catalog=try{api().json("/api/codex/sessions/${q(originalSession!!)}/queue")}
                    catch(e: ApiRequestFailure){if(e.status==404) obj("mode" to "local") else throw e}
                val nativeRows=catalog?.array("data")?.objects().orEmpty()
                for(row in current) {
                    val activeAccount=identity()
                    if(activeAccount.isNullOrBlank())continue
                    if(row.accountId!=activeAccount) {
                        update(row.key){it.copy(status="队列所属账号已变化，请切回原账号或移除")}
                        continue
                    }
                    if(row.mode.isEmpty() && row.status in setOf("入队结果待核对","入队状态待核对")) {
                        val result=api().json("/api/codex/runs/lookup?key=${q(row.key)}")
                        val sameSession=result.optBoolean("found") && result.optString("session_id")==row.session
                        val existing=nativeRows.firstOrNull { it.optString("clientUserMessageId")==row.key }
                        when {
                            sameSession && result.optString("status") in setOf("started","completed","interrupted","failed","cancelled") &&
                                result.optString("delivery")!="rejected" -> accepted(row.key)
                            existing!=null -> update(row.key){it.copy(mode="native",nativeId=existing.getString("id"),status="等待任务结束")}
                            sameSession && result.optString("delivery")=="rejected" -> update(row.key){it.copy(status="入队失败，请编辑或移除")}
                            else -> update(row.key){it.copy(status="入队状态待核对")}
                        }
                        continue
                    }
                    if(row.mode.isEmpty()) {
                        if(row.status.startsWith("入队失败"))continue
                        val mode=if(agent=="codex")catalog?.optString("mode","local") ?: "local" else "local"
                        if(mode=="native") {
                            val existing=nativeRows.firstOrNull { it.optString("clientUserMessageId")==row.key }
                            if(existing!=null) update(row.key){it.copy(mode="native",nativeId=existing.getString("id"),status="等待任务结束")}
                            else {
                                // Native admission can start immediately when idle. Allow editing first.
                                if(paused || panelOpen && ready(originalSession))continue
                                update(row.key){it.copy(status="入队结果待核对")}
                                try {
                                    val response=native(row,"add").getJSONObject("queuedSubmission")
                                    update(row.key){it.copy(mode="native",nativeId=response.getString("id"),status="等待任务结束")}
                                } catch(e: Exception) {
                                    if(e is CancellationException)throw e
                                    if(writeWasRejected(e))update(row.key){it.copy(status="入队失败：${connectionFailureMessage(e)}")}
                                    throw e
                                }
                            }
                        } else update(row.key){it.copy(mode="local",status="等待任务结束")}
                    } else if(row.mode=="native" && catalog?.optString("mode")=="local") {
                        // The old native writer may still own this submission. Reconcile
                        // its durable key; never fall through to a fresh local send.
                        val result=api().json("/api/codex/runs/lookup?key=${q(row.key)}")
                        if(result.optBoolean("found") && result.optString("session_id")==row.session &&
                            result.optString("status") in setOf("started","completed","interrupted","failed","cancelled")) accepted(row.key)
                        else update(row.key){it.copy(status="原生队列连接待恢复")}
                    } else if(row.mode=="native" && catalog?.optString("mode")=="native" && row.nativeId!=null &&
                        nativeRows.none { it.optString("id")==row.nativeId }) {
                        val result=api().json("/api/codex/runs/lookup?key=${q(row.key)}")
                        if(result.optBoolean("found") && result.optString("session_id")==row.session &&
                            result.optString("status") in setOf("started","completed","interrupted","failed","cancelled")) accepted(row.key)
                        else update(row.key){it.copy(status="原生队列状态待核对")}
                    }
                }
                val row=entries.firstOrNull { it.session == targetSession && it.accountId == identity() } ?: return
                // Native queues drain automatically, including while the phone is offline.
                if(row.mode=="native")return
                if(row.status!="等待任务结束" || panelOpen || paused || !ready(targetSession))return
                // Verify fresh server state. Missing/expired observations never mean idle.
                val running=api().json("/api/$agent/sessions/${q(row.session)}/active-run")
                if(running.optString("status") in setOf("started","submitting","running","queued","stopping","waiting_for_approval"))return
                val activity=api().json("/api/$agent/sessions/${q(row.session)}/activity")
                if(!activity.optBoolean("available") || activity.optBoolean("running"))return
                if(panelOpen || paused || !ready(targetSession))return
                update(row.key){it.copy(status="发送中")}
                dispatch(row,null)
        } catch(e: CancellationException){throw e}
        catch(e: Exception){
                entries.firstOrNull { it.session==targetSession && it.status=="发送中" }?.let{failed(it.key,writeWasRejected(e))}
                // Read failures retain durable entries and are retried by the next foreground observation.
        } finally{busy=false}
    }
}
