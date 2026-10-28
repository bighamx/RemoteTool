package com.chuckiehelper.mobile.nativeui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import okio.source
import org.json.JSONArray
import org.json.JSONObject

internal data class CfLocation(val operation: String, val title: String, val args: JSONObject = JSONObject())

/** UI confirmation only: anything except one SELECT/EXPLAIN statement asks before execution. */
internal fun cloudflareReadOnlySql(sql: String): Boolean {
    val visible = StringBuilder()
    var index = 0
    while (index < sql.length) {
        val current = sql[index]
        when {
            sql.startsWith("--", index) -> { index = sql.indexOf('\n', index).takeIf { it >= 0 } ?: sql.length; visible.append(' ') }
            sql.startsWith("/*", index) -> { val end = sql.indexOf("*/", index + 2); if (end < 0) return false; index = end + 2; visible.append(' ') }
            current in listOf('\'', '"', '`', '[') -> {
                val close = if (current == '[') ']' else current
                index++; var closed = false
                while (index < sql.length) {
                    if (sql[index] == close) {
                        if (index + 1 < sql.length && sql[index + 1] == close) { index += 2; continue }
                        index++; closed = true; break
                    }
                    index++
                }
                if (!closed) return false
                visible.append(" ? ")
            }
            else -> { visible.append(current); index++ }
        }
    }
    val statement = visible.toString().trim().removeSuffix(";").trimEnd()
    return ';' !in statement && Regex("^(SELECT|EXPLAIN)\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement)
}

internal class CloudflareModel(val api: NativeApi, private val scope: CoroutineScope, private val onError: (String) -> Unit,
    private val cloud: CloudflareAccountSource,
    private val listings: CloudflareListingCache = CloudflarePageCache.forOwner(cloud.subject),
    private val json: suspend (String, JSONObject?) -> JSONObject = { path, body -> api.json(path, body) }) {
    private val owner = cloud.subject
    private fun requireOwner() { require(owner.isNotBlank() && cloud.subject == owner) { "请先登录当前云账户" } }
    var accounts by mutableStateOf<List<JSONObject>>(emptyList())
    var account by mutableStateOf<JSONObject?>(null)
    var legacyCount by mutableIntStateOf(0)
    var routeDomains by mutableStateOf<List<String>>(emptyList())
    suspend fun loadRouteDomains() {
        try {
            val names = mutableListOf<String>()
            for (page in 1..100) {
                val data = call("zones", obj("page" to page)) as? JSONObject ?: break
                names += data.optJSONArray("items")?.objects().orEmpty().map { it.getString("name") }
                if (!data.optBoolean("hasMore")) break
            }
            routeDomains = names.distinct().sorted()
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { /* Domain suggestions are optional; complete hostnames remain editable. */ }
    }
    var tab by mutableIntStateOf(listings.selectedTab.coerceIn(0, 3))
    val stack = mutableStateListOf<CfLocation>()
    var items by mutableStateOf<List<JSONObject>>(emptyList())
    var results by mutableStateOf<List<JSONObject>>(emptyList())
    var detail by mutableStateOf<JSONObject?>(null)
    var busy by mutableStateOf(false)
    var refreshing by mutableStateOf(false); private set
    var accountsRefreshing by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)
    var hasMore by mutableStateOf(false)
    var queryMode by mutableStateOf(false)
    private var page = 1
    private var cursor: String? = null
    private var refreshJob: Job? = null
    private var accountsJob: Job? = null
    private var legacyJob: Job? = null
    private var readEpoch = 0L
    val location get() = stack.lastOrNull() ?: CfLocation(listOf("zones", "r2.buckets", "d1.list", "tunnels.list")[tab], listOf("域名", "存储桶", "数据库", "隧道")[tab])
    val root get() = "/api/cloudflare/accounts/${account?.optString("id").orEmpty()}"

    fun work(block: suspend () -> Unit) {
        if (busy) return
        accountsJob?.cancel(); accountsRefreshing = false
        legacyJob?.cancel()
        cancelRefresh()
        busy = true; error = null; info = null
        scope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "操作失败"; if (error.orEmpty().contains("登录已过期")) onError(error!!) }
            finally { busy = false }
        }
    }
    private fun cancelRefresh() { readEpoch++; refreshJob?.cancel(); refreshJob = null; refreshing = false }
    private fun cacheAccountId(value: JSONObject?): String = value?.let {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(accountSignature(it)!!.toByteArray())
            .joinToString("") { byte -> (byte.toInt() and 255).toString(16).padStart(2, '0') }
        it.getString("id") + ":" + digest
    }.orEmpty()
    val listingKey get() = location.key(cacheAccountId(account))
    private fun applySnapshot(value: CfListingSnapshot) {
        items = JSONArray(value.items).objects(); results = JSONArray(value.results).objects()
        detail = value.detail?.let(::JSONObject); hasMore = value.hasMore; page = value.page; cursor = value.cursor; queryMode = false
    }
    private fun navigate(change: () -> Unit) {
        if (busy) return
        cancelRefresh(); change(); error = null; info = null
        val target = location; val selected = account?.let { JSONObject(it.toString()) } ?: return
        val accountId = cacheAccountId(selected)
        val key = target.key(accountId); val cached = listings.get(key)
        applySnapshot(cached ?: CfListingSnapshot())
        val epoch = readEpoch
        refreshing = true
        refreshJob = scope.launch {
            try {
                var refreshed = CfListingSnapshot()
                for (nextPage in 1..(cached?.page ?: 1)) {
                    val arguments = JSONObject(target.args.toString()).put("page", nextPage).put("operation", target.operation)
                    refreshed.cursor?.let { arguments.put("cursor", it) }
                    val response = callFor(selected, target.operation, arguments)
                    ensureActive()
                    refreshed = cfListingSnapshot(target.operation, response, nextPage, JSONArray(refreshed.items).objects())
                    if (!refreshed.hasMore) break
                }
                if (readEpoch == epoch && listingKey == key) { listings.put(key, refreshed); applySnapshot(refreshed) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { if (readEpoch == epoch && listingKey == key) error = failure.message ?: "加载失败，请重试" }
            finally { if (readEpoch == epoch) refreshing = false }
        }
    }
    suspend fun call(operation: String, args: JSONObject = JSONObject()): Any? {
        return callFor(account ?: error("请选择 Cloudflare 账户"), operation, args)
    }
    private suspend fun callFor(selected: JSONObject, operation: String, args: JSONObject): Any? {
        requireOwner()
        val body = JSONObject(args.toString()).put("operation", operation).put("account", selected)
        return try { json("/api/cloudflare/execute", body).opt("data").also { requireOwner() } }
        catch (failure: ApiRequestFailure) {
            if (failure.status == 404) throw java.io.IOException("当前设备服务端版本较旧，请更新后使用 Cloudflare 云配置")
            throw failure
        }
    }
    fun initialize() {
        if (owner.isBlank()) return
        applyAccounts(cloud.cachedCloudflareAccounts(), listings.selectedAccountId)
        if (account != null) navigate { }
        accountsRefreshing = true
        accountsJob = scope.launch {
            try {
                val rows = cloud.cloudflareAccounts(); requireOwner()
                val previous = account
                val selectedId = previous?.optString("id")
                applyAccounts(rows, selectedId)
                val changed = accountSignature(previous) != accountSignature(account)
                if (changed) {
                    listings.clear()
                    navigate { stack.clear() }
                }
                if (account == null) { cancelRefresh(); applySnapshot(CfListingSnapshot()); checkLegacy() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: "云配置刷新失败，请稍后重试" }
            finally { accountsRefreshing = false }
        }
    }
    private fun accountSignature(value: JSONObject?) = value?.let { row ->
        JSONArray(listOf("id", "accountId", "apiToken", "accessKeyId", "secretAccessKey", "bucket").map { row.optString(it) }).toString()
    }
    private fun applyAccounts(rows: JSONArray, prefer: String?) {
        accounts = rows.objects().map { JSONObject(it.toString()).put("hasToken", it.optString("apiToken").isNotBlank()).put("hasR2", it.optString("secretAccessKey").isNotBlank()) }
        account = accounts.firstOrNull { it.optString("id") == prefer } ?: accounts.firstOrNull()
        listings.selectedAccountId = account?.optString("id")
        if (account != null) legacyCount = 0
    }
    private fun checkLegacy() {
        legacyJob?.cancel()
        legacyJob = scope.launch {
            try {
                val count = withTimeout(5000) { json("/api/cloudflare/accounts", null).optJSONArray("data")?.length() ?: 0 }
                requireOwner(); if (accounts.isEmpty()) legacyCount = count
            } catch (_: TimeoutCancellationException) { }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { /* Optional migration check must never hold up the page. */ }
        }
    }
    private suspend fun refreshAccounts(prefer: String? = account?.optString("id")) {
        requireOwner()
        val rows = cloud.cloudflareAccounts()
        requireOwner()
        applyAccounts(rows, prefer)
        stack.clear(); if (account != null) load() else { applySnapshot(CfListingSnapshot()); checkLegacy() }
    }
    fun importLegacy() = work {
        requireOwner()
        val legacy = json("/api/cloudflare/accounts/export", JSONObject()).getJSONArray("data").objects()
        requireOwner()
        cloud.updateCloudflareAccounts { current, _ ->
            requireOwner()
            val existing = current.objects()
            val accountIds = existing.map { it.getString("accountId").lowercase() }.toSet()
            val ids = existing.map { it.getString("id") }.toMutableSet()
            JSONArray(existing + legacy.filter { it.getString("accountId").lowercase() !in accountIds }.distinctBy { it.getString("accountId").lowercase() }.map {
                JSONObject(it.toString()).apply {
                    if (!ids.add(getString("id"))) put("id", java.util.UUID.randomUUID().toString().replace("-", ""))
                }
            })
        }
        requireOwner(); listings.clear(); refreshAccounts(); info = "已导入云账户"
    }
    fun choose(value: JSONObject) = navigate { account = value; listings.selectedAccountId = value.getString("id"); stack.clear() }
    fun selectTab(value: Int) = navigate { tab = value; listings.selectedTab = value; stack.clear() }
    fun open(value: CfLocation) = navigate { stack += value }
    fun back(close: () -> Unit) { if (busy) return; if (stack.isEmpty()) { cancelRefresh(); close() } else navigate { stack.removeAt(stack.lastIndex) } }
    fun dnsFilter(key: String, value: String) = navigate {
        val current = location
        stack[stack.lastIndex] = current.copy(args = JSONObject(current.args.toString()).put(key, value))
    }
    fun reload() = navigate { }
    fun more() = work { page++; try { load(append = true) } catch (failure: Exception) { page--; throw failure } }
    private suspend fun load(append: Boolean = false) {
        if (!append) { page = 1; cursor = null; items = emptyList(); results = emptyList(); detail = null; queryMode = false }
        val target = location
        val args = JSONObject(target.args.toString()).put("page", page)
        cursor?.let { args.put("cursor", it) }
        val data = call(target.operation, args)
        val snapshot = cfListingSnapshot(target.operation, data, page, if (append) items else emptyList())
        applySnapshot(snapshot); listings.put(listingKey, snapshot)
    }
    fun nextRows() = work {
        val current = location
        val offset = current.args.optInt("offset") + 100
        stack[stack.lastIndex] = current.copy(args = JSONObject(current.args.toString()).put("offset", offset)); load()
    }
    fun previousRows() = work {
        val current = location
        stack[stack.lastIndex] = current.copy(args = JSONObject(current.args.toString()).put("offset", (current.args.optInt("offset") - 100).coerceAtLeast(0))); load()
    }
    fun saveAccount(body: JSONObject) = work {
        requireOwner()
        val id = body.optString("id").ifBlank { java.util.UUID.randomUUID().toString().replace("-", "") }
        cloud.updateCloudflareAccounts { rows, _ ->
        requireOwner(); val latest = rows.objects()
        val old = latest.firstOrNull { it.optString("id") == id }
        require(body.optString("id").isBlank() || old != null) { "配置已不存在，请刷新" }
        val saved = JSONObject(body.toString()).put("id", id)
        for (key in listOf("name", "accountId", "bucket")) saved.put(key, body.optString(key).trim())
        for (key in listOf("apiToken", "accessKeyId", "secretAccessKey")) {
            saved.put(key, body.optString(key).trim().ifBlank { old?.optString(key).orEmpty() })
        }
        if (body.optBoolean("clearR2")) { saved.put("accessKeyId", ""); saved.put("secretAccessKey", "") }
        JSONArray(latest.filter { it.optString("id") != id } + saved)
        }
        requireOwner(); listings.clear(); refreshAccounts(id)
    }
    fun removeAccount() = work {
        requireOwner(); val id = account?.getString("id") ?: return@work
        cloud.updateCloudflareAccounts { rows, _ -> requireOwner(); JSONArray(rows.objects().filter { it.getString("id") != id }) }
        requireOwner(); listings.clear(); account = null; refreshAccounts()
    }
    fun mutate(operation: String, args: JSONObject) = work { call(operation, args); load(); info = "已完成" }
    fun removeTunnel() = work { call("tunnels.delete", location.args); stack.removeAt(stack.lastIndex); load(); info = "已删除" }
    fun removeDatabase() = work { call("d1.delete", location.args); stack.clear(); load(); info = "已删除" }
    fun query(sql: String, parameters: String) = work {
        val args = JSONObject(location.args.toString()).put("sql", sql).put("parameters", JSONArray(parameters.ifBlank { "[]" }))
        results = (call("d1.query", args) as? JSONArray)?.objects().orEmpty(); items = emptyList(); hasMore = false; queryMode = true; info = "查询完成"
    }
    fun schema() = work { results = (call("d1.schema", location.args) as? JSONArray)?.objects().orEmpty(); queryMode = true; hasMore = false; info = "表结构" }
    fun inspect(operation: String, receive: (JSONObject) -> Unit) = work {
        receive(call(operation, location.args) as? JSONObject ?: JSONObject())
    }
    fun saveTunnelConfig(config: JSONObject, saved: () -> Unit) = work {
        call("tunnels.saveConfig",JSONObject(location.args.toString()).put("config",config));load();saved();info="路由已保存"
    }
    fun upload(context: Context, uri: Uri) = work {
        var name = "upload"; var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { rows ->
            if (rows.moveToFirst()) { name = rows.getString(0) ?: name; if (!rows.isNull(1)) size = rows.getLong(1) }
        }
        require(size <= 100L * 1024 * 1024) { "文件不能超过 100 MB" }
        val args = location.args; val key = args.optString("prefix") + name.substringAfterLast('/').substringAfterLast('\\')
        val content = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                context.contentResolver.openInputStream(uri)?.use { input -> sink.writeAll(input.source()) } ?: error("无法读取文件")
            }
        }
        requireOwner()
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("account", account!!.toString()).addFormDataPart("file", name, content).build()
        api.json(api.request("/api/cloudflare/r2/upload?bucket=${q(args.getString("bucket"))}&key=${q(key)}").newBuilder().post(body).build())
        requireOwner()
        load(); info = "上传完成"
    }
    fun download(context: Context, file: JSONObject, uri: Uri) = work {
        val bucket = location.args.getString("bucket")
        requireOwner()
        api.streamingResponse(api.request("/api/cloudflare/r2/download", obj("account" to account, "bucket" to bucket, "key" to file.getString("key")))) { response ->
            if (!response.isSuccessful) throw java.io.IOException("下载失败（${response.code}）")
            withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { output -> response.body?.byteStream()?.use { it.copyTo(output) } ?: error("下载内容为空") } ?: error("无法写入文件")
            }
        }
        info = "下载完成"
    }
    companion object {
        fun queryRows(data: Any?): List<JSONObject> = (data as? JSONArray)?.objects()?.flatMap { it.optJSONArray("results")?.objects().orEmpty() }.orEmpty()
    }
}
