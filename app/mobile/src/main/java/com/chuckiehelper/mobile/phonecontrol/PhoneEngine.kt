package com.chuckiehelper.mobile.phonecontrol

import android.content.Context
import android.content.Intent
import android.accessibilityservice.AccessibilityService
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock

class PhoneEngine(val context: Context) {
    val settings = PhoneSettings(context)
    val privilege = PhonePrivilege(context, settings)
    val agentUi = PhoneAgentUi(context.applicationContext)
    private val journal = PhoneJournal(context)
    val connectionLog = PhoneConnectionLog(context)
    private val control = ReentrantLock()
    private val files = ReentrantLock()
    private val connectionGate = Any()
    private val declaredNames = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile var generation = 0L; private set
    fun stop() { generation++; settings.enabled = false; declaredNames.clear(); connectionLog.disconnectAll("service_stopped"); agentUi.close(); PhoneAccessibility.current?.clearSnapshots(); privilege.close() }
    fun connectionSeen(owner: String, name: String, details: JSONObject = JSONObject()) {
        synchronized(connectionGate) {
            val displayName = declaredNames[owner] ?: name
            connectionLog.seen(owner, displayName, details = details); agentUi.seen(owner, displayName)
        }
    }
    fun disconnect(owner: String, byUser: Boolean = false) {
        synchronized(connectionGate) {
        connectionLog.disconnect(owner, if (byUser) "user_disconnected" else "agent_disconnected", byUser)
        agentUi.disconnected(owner); PhoneAccessibility.current?.clearSnapshots(owner)
        declaredNames.remove(owner)
        }
    }
    fun webDisconnected() { connectionLog.disconnectAll("relay_disconnected", "web:"); declaredNames.keys.removeAll { it.startsWith("web:") }; agentUi.webDisconnected() }
    internal fun connectionStates() = agentUi.connectionStates()
    fun touchConnection(owner: String) {
        synchronized(connectionGate) { connectionLog.touch(owner); agentUi.touchConnection(owner) }
    }
    fun capabilities(): JSONObject = settings.description().put("schema_version", PhoneToolCatalog.VERSION)
        .put("app", JSONObject().put("package_name", com.chuckiehelper.mobile.BuildConfig.APPLICATION_ID)
            .put("version_name", com.chuckiehelper.mobile.BuildConfig.VERSION_NAME).put("version_code", com.chuckiehelper.mobile.BuildConfig.VERSION_CODE))
        .put("advanced", settings.advanced).put("accessibility", PhoneAccessibility.current != null)
        .put("notification_access", PhoneNotifications.current != null).put("tool_count", PhoneToolCatalog.tools(settings.advanced).length())
        .put("notification_authorized", PhonePermissionStatus.notificationGranted(context) ?: JSONObject.NULL)
        .put("accessibility_authorized", PhonePermissionStatus.accessibilityGranted(context) ?: JSONObject.NULL)
        .put("privilege", privilege.status()).put("android_api", android.os.Build.VERSION.SDK_INT)
        .put("agent_display", agentUi.capabilities())
        .put("observation", JSONObject().put("backends", JSONArray(listOf("auto", "accessibility", "uiautomator")))
            .put("default_backend", "auto").put("include_unimportant_default", true)
            .put("uiautomator_node_actions", false).put("uiautomator_requires", "authorized Shizuku or root"))
        .apply { val screen = PhoneScreen.info(context); screen.keys().forEach { put(it, screen.get(it)) } }
    fun status(id: String) = journal.status(id)
    fun invoke(name: String, arguments: JSONObject, requestId: String, allowAdvanced: Boolean = true,
        deadline: Long = System.currentTimeMillis() + PhoneToolPolicy.timeoutMs(name, arguments), agentId: String = "direct", agentName: String = "Agent", auditId: String? = null, queueMs: Long = 0): JSONObject {
        val effectiveName = declaredNames[agentId] ?: agentName
        val log = try { auditId ?: connectionLog.begin(agentId, effectiveName, requestId, name, arguments) }
            catch (error: PhoneToolError) { return errorResult(error) }
        var started = false
        val result = invokeCore(name, arguments, requestId, allowAdvanced, deadline, agentId, effectiveName, queueMs) { started = true; connectionLog.markRunning(log) }
        annotate(result, requestId, if (started) "receipt_available" else "not_started")
        if (connectionLog.blocked(agentId)) result.optJSONObject("structuredContent")?.apply {
            put("connection_closed_by_user", true); put("retryable", false)
            put("connection_message", "用户已在手机端断开此连接。该调用若已开始，请核对本次执行回执；后续操作需重新建立会话。")
            put("execution", if (optString("code") == "delivery_unknown") "unknown" else if (!started) "not_started" else "receipt_available")
            optJSONObject("details")?.put("execution", optString("execution"))
            result.optJSONArray("content")?.optJSONObject(0)?.takeIf { it.optString("type") == "text" }?.put("text", toString())
        }
        connectionLog.finish(log, result)
        return result
    }
    private fun annotate(result: JSONObject, requestId: String, fallback: String) {
        result.optJSONObject("structuredContent")?.let { data ->
            if (!data.has("operation_id")) data.put("operation_id", requestId)
            if (!data.has("execution")) data.put("execution", if (data.optString("code") == "delivery_unknown") "unknown" else fallback)
            if (result.optBoolean("isError") && !data.has("retryable")) data.put("retryable", data.optString("code") in setOf("device_busy", "ui_busy") && data.optString("execution") == "not_started")
            if (result.optBoolean("isError") && !data.has("hint")) data.put("hint", when (data.optString("code")) {
                "stale_snapshot", "node_not_found", "ambiguous_target", "target_not_visible", "node_not_actionable", "precondition_failed" -> "重新观察目标应用，选择唯一且可见的控件；不要猜坐标或盲目重发。"
                "postcondition_timeout", "delivery_unknown" -> "动作可能已发生，请使用 operation_id 查询回执并核对界面，不要换新 ID 重发。"
                "connection_closed_by_user" -> "用户已断开，请停止；仅在用户明确要求继续后开始新任务。"
                else -> "核对返回的 code、权限及当前状态后再决定下一步。"
            })
            result.optJSONArray("content")?.optJSONObject(0)?.takeIf { it.optString("type") == "text" }?.put("text", data.toString())
        }
    }
    private fun invokeCore(name: String, arguments: JSONObject, requestId: String, allowAdvanced: Boolean,
        deadline: Long, agentId: String, agentName: String, queueMs: Long, onStarted: () -> Unit): JSONObject {
        try {
            connectionLog.requireOpen(agentId)
            if (!settings.enabled) fail("disabled", "手机操控未开启")
            if (!Regex("[a-zA-Z0-9_.:-]{1,160}").matches(requestId)) fail("invalid_arguments", "Invalid request_id")
            PhoneToolCatalog.validate(name, arguments)
            PhoneExecution.validateOptions(arguments)
            if (name in setOf("phone_observe", "phone_find_nodes")) PhoneExecution.validateObservation(arguments)
            if (name == "phone_begin_ui" && arguments.has("client_name")) {
                val label = arguments.getString("client_name")
                if (label.isBlank() || label.length > 40 || label.any { it.isISOControl() }) fail("invalid_arguments", "client_name 必须为 1–40 个可显示字符")
            }
            if (name in setOf("phone_execute", "phone_tap", "phone_swipe", "phone_gesture")) {
                val screen = PhoneScreen.info(context)
                if (name == "phone_execute") PhoneExecution.validateBatch(arguments).forEach { (tool, args) ->
                    PhoneExecution.validateCoordinates(tool, args, screen.getInt("screen_width"), screen.getInt("screen_height"))
                }
                PhoneExecution.validateCoordinates(name, arguments, screen.getInt("screen_width"), screen.getInt("screen_height"))
            }
            if (name == "phone_wait") PhoneExecution.validateWait(arguments)
            val explanation = arguments.optString("explanation").trim()
            val operationArguments = JSONObject(arguments.toString()).apply { remove("explanation") }
            if (name in PhoneToolCatalog.advanced && (!settings.advanced || !allowAdvanced)) fail("advanced_disabled", "高级操作未开启")
            val digest = MessageDigest.getInstance("SHA-256").digest((agentId + ":" + name + ":" + allowAdvanced + ":" + canonical(operationArguments)).toByteArray()).joinToString("") { "%02x".format(it) }
            journal.existing(requestId, digest)?.let { return it }
            // Assistance and metadata must stay available even while an Android UI
            // binder call is slow. They never execute arbitrary device mutations.
            val ui = PhoneToolPolicy.requiresUi(name, operationArguments)
            val operationLock = when {
                name in setOf("phone_begin_ui", "phone_end_ui") -> control
                ui && name != "phone_wait" -> control
                name in setOf("phone_files", "phone_transfer_file", "phone_manage_app") -> files
                else -> null
            }
            if (operationLock != null && !operationLock.tryLock()) fail("device_busy", "手机正在执行其他操作，请稍后重试")
            try {
                if (!settings.enabled || deadline < System.currentTimeMillis()) fail("timeout", "请求已过期或控制已停止")
                journal.claim(requestId, digest, agentId)?.let { return it }
                connectionSeen(agentId, agentName)
                val epoch = generation
                val hasIndicator = agentUi.toolStarted(agentId, name, explanation)
                val startedAt = android.os.SystemClock.elapsedRealtime()
                var attempted = false
                val result = try {
                    synchronized(connectionGate) { connectionLog.requireOpen(agentId) }
                    if (ui && name != "phone_wait") agentUi.requireUi(agentId)
                    if (name in setOf("phone_tap", "phone_input_text", "phone_ui_action") && operationArguments.optString("node_id").matches(Regex("p[0-9]+")))
                        fail("read_only_backend", "UiAutomation 节点只读，请用 backend=accessibility 重新观察后操作")
                    checkGuard(operationArguments, agentId)
                    val resolved = if (name in setOf("phone_tap", "phone_input_text")) resolveSelector(operationArguments, agentId) else operationArguments
                    val before = if (name in PhoneExecution.actions && resolved.has("wait_for")) observe(agentId) else null
                    PhoneOperationAdmission.begin(connectionGate,
                        {
                            connectionLog.requireOpen(agentId)
                            if (generation != epoch || !settings.enabled) fail("cancelled", "操控已停止，未进入工具主体")
                            if (deadline < System.currentTimeMillis()) fail("timeout", "请求已过期，未进入工具主体")
                        }, { journal.markStarted(requestId) },
                        { onStarted(); attempted = true })
                    val data = if (name == "phone_execute") batch(resolved, requestId, allowAdvanced, epoch, deadline, agentId, agentName)
                        else execute(name, resolved, epoch, deadline, agentId)
                    val failed = data.has("exit_code") && data.optInt("exit_code", -1) != 0 || data.optBoolean("timed_out") || data.has("accepted") && !data.optBoolean("accepted") || data.has("code") && name == "phone_execute"
                    if (failed && !data.has("code")) data.put("code", if (data.optBoolean("timed_out")) "delivery_unknown" else "operation_failed")
                    if (!failed && name in PhoneExecution.actions) {
                        resolved.optJSONObject("wait_for")?.let { condition ->
                            val state = waitFor(condition, agentId, epoch, deadline, before)
                            before?.let { data.put("state_before", PhoneExecution.stateMarker(it)) }
                            data.put("matched", state.getBoolean("matched")).put("state", state)
                            if (!state.getBoolean("matched")) data.put("code", "postcondition_timeout")
                        }
                        val mode = resolved.optString("return_state", "none")
                        if (mode != "none" && !data.has("state")) data.put("state", observe(agentId, JSONObject().put("mode", mode)))
                    }
                    JSONObject().put("isError", failed).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString())))
                        .put("structuredContent", data)
                } catch (error: Exception) { errorResult(error) }
                finally { if (hasIndicator) agentUi.toolFinished(agentId) }
                val receipt = result.getJSONObject("structuredContent")
                receipt.put("operation_id", requestId).put("execution", if (receipt.optString("code") == "delivery_unknown") "unknown" else if (!attempted) "not_started" else "receipt_available")
                    .put("metrics", JSONObject().put("device_ms", android.os.SystemClock.elapsedRealtime() - startedAt).put("queue_ms", queueMs.coerceAtLeast(0)))
                if (receipt.optString("code") == "postcondition_timeout") result.put("isError", true)
                result.getJSONArray("content").getJSONObject(0).put("text", receipt.toString())
                // Large image data is carried separately from structured metadata.
                result.optJSONObject("structuredContent")?.optString("image_base64")?.takeIf { it.isNotBlank() }?.let { image ->
                    val data = result.getJSONObject("structuredContent"); val mime = data.optString("image_mime", "image/jpeg")
                    val imageBytes = Base64.decode(image, Base64.DEFAULT)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                    data.put("image_width", bounds.outWidth).put("image_height", bounds.outHeight)
                    if (bounds.outWidth > 0 && bounds.outHeight > 0) data.put("image_to_screen", JSONObject()
                        .put("scale_x", data.optInt("screen_width", PhoneScreen.info(context).getInt("screen_width")).toDouble() / bounds.outWidth)
                        .put("scale_y", data.optInt("screen_height", PhoneScreen.info(context).getInt("screen_height")).toDouble() / bounds.outHeight))
                    data.remove("image_base64"); result.getJSONArray("content").getJSONObject(0).put("text", data.toString())
                    result.getJSONArray("content").put(JSONObject().put("type", "image").put("mimeType", mime).put("data", image))
                }
                annotate(result, requestId, if (attempted) "receipt_available" else "not_started")
                journal.finish(requestId, result)
                return result
            } finally { operationLock?.unlock() }
        } catch (error: Exception) { return errorResult(error) }
    }
    private fun execute(name: String, a: JSONObject, epoch: Long, deadline: Long, agentId: String): JSONObject {
        fun accepted(ok: Boolean) = JSONObject().put("accepted", ok).put("verified", false).put("backend", "accessibility")
        fun service() = PhoneAccessibility.requireService()
        return when (name) {
            "phone_disconnect" -> {
                disconnect(agentId)
                JSONObject().put("disconnected", true).put("service_stopped", false)
            }
            "phone_begin_ui" -> synchronized(connectionGate) {
                connectionLog.requireOpen(agentId)
                if (a.has("client_name") && !declaredNames.containsKey(agentId) && declaredNames.size >= 256) fail("device_busy", "客户端名称记录已达上限")
                agentUi.beginUi(agentId, a.optString("message", "正在操作屏幕"), 300)
                if (a.has("client_name")) {
                    declaredNames[agentId] = a.getString("client_name")
                    connectionSeen(agentId, a.getString("client_name"))
                }
                agentUi.capabilities().put("declared", true).put("idle_timeout_seconds", 300)
                    .put("client_name", declaredNames[agentId] ?: connectionStates().firstOrNull { it.id == agentId }?.name ?: "Agent")
            }
            "phone_end_ui" -> synchronized(connectionGate) {
                connectionLog.requireOpen(agentId)
                agentUi.endUi(agentId)
                a.optString("message").takeIf { it.isNotBlank() }?.let { agentUi.notify(agentId, it, false, 10) }
                JSONObject().put("declared", false)
            }
            "phone_notify_user" -> synchronized(connectionGate) {
                connectionLog.requireOpen(agentId)
                val kind = a.optString("kind", "info")
                if (kind !in setOf("info", "help") || a.getString("message").isBlank() || a.getString("message").length > 1000)
                    fail("invalid_arguments", "kind 必须为 info/help，message 长度为 1–1000")
                agentUi.notify(agentId, a.getString("message"), kind == "help", a.optInt("timeout_seconds", if (kind == "help") 600 else 15))
            }
            "phone_get_user_reply" -> agentUi.result(agentId, a.getString("notice_id"))
            "phone_list_devices" -> JSONObject().put("devices", JSONArray().put(settings.description()))
            "phone_get_capabilities" -> capabilities()
            "phone_get_operation" -> {
                val id = a.getString("operation_id")
                val previous = journal.status(id, agentId)
                previous
            }
            "phone_observe", "phone_find_nodes" -> {
                val query = JSONObject(a.toString())
                if (name == "phone_find_nodes") {
                    val fields = listOf("text", "resource_id", "description").filter { a.has(it) }
                    if (fields.isEmpty()) fail("invalid_arguments", "至少提供一个查询条件")
                    query.put("_find", JSONObject().apply { fields.forEach { put(it, a.getString(it)) } })
                }
                val data = observe(agentId, query)
                if (name == "phone_find_nodes") {
                    val fields = listOf("text", "resource_id", "description").filter { a.has(it) }
                    if (fields.isEmpty()) fail("invalid_arguments", "至少提供一个查询条件")
                    val rows = data.getJSONArray("nodes"); val matches = JSONArray()
                    for (i in 0 until rows.length()) { val row = rows.getJSONObject(i)
                        if (fields.all { row.optString(it).contains(a.getString(it), true) }) matches.put(row) }
                    data.put("nodes", matches)
                }
                if (a.optBoolean("screenshot")) {
                    val bytes = if (PhoneAccessibility.current != null && android.os.Build.VERSION.SDK_INT >= 30) service().screenshot() else privilege.screenshot("auto")
                    data.put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("image_mime", "image/jpeg")
                }
                data
            }
            "phone_tap" -> if (PhoneAccessibility.current != null) accepted(service().tap(a, agentId)) else {
                if (a.has("node_id") || a.optBoolean("long_press")) fail("permission_required", "节点/长按操作需要无障碍服务")
                privilege.execute(listOf("input", "tap", a.getInt("x").toString(), a.getInt("y").toString()))
            }
            "phone_swipe" -> if (PhoneAccessibility.current != null) accepted(service().gesture(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(), a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat(), a.optLong("duration_ms", 400)))
                else privilege.execute(listOf("input", "swipe") + listOf("x1", "y1", "x2", "y2").map { a.getInt(it).toString() } + a.optInt("duration_ms", 400).coerceIn(1, 3000).toString())
            "phone_input_text" -> if (PhoneAccessibility.current != null) accepted(service().input(a, agentId)) else {
                if (a.has("node_id") || a.getString("text").any { it.code !in 32..126 }) fail("permission_required", "Unicode/节点输入需要无障碍服务；也可通过特权 Shell 配合设备输入法")
                privilege.execute(listOf("input", "text", a.getString("text").replace(" ", "%s")))
            }
            "phone_ui_action" -> accepted(service().uiAction(a, agentId))
            "phone_gesture" -> accepted(service().multiGesture(a))
            "phone_screenshot" -> {
                val backend = a.optString("backend", "auto")
                if (backend !in setOf("auto", "accessibility", "shizuku", "root")) fail("invalid_arguments", "Invalid screenshot backend")
                val accessibility = backend == "accessibility" || backend == "auto" && PhoneAccessibility.current != null && android.os.Build.VERSION.SDK_INT >= 30
                val bytes = if (accessibility) service().screenshot() else privilege.screenshot(backend)
                JSONObject().put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("image_mime", "image/jpeg").put("coordinate_space", "original_screen_pixels")
                    .apply { val screen = PhoneScreen.info(context); screen.keys().forEach { put(it, screen.get(it)) } }
            }
            "phone_key" -> {
                val key = a.getString("key").uppercase()
                val globalActions = mutableMapOf("BACK" to AccessibilityService.GLOBAL_ACTION_BACK, "HOME" to AccessibilityService.GLOBAL_ACTION_HOME, "RECENTS" to AccessibilityService.GLOBAL_ACTION_RECENTS, "NOTIFICATIONS" to AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS,
                    "QUICK_SETTINGS" to AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "POWER_DIALOG" to AccessibilityService.GLOBAL_ACTION_POWER_DIALOG, "SPLIT_SCREEN" to AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
                if (android.os.Build.VERSION.SDK_INT >= 28) { globalActions["LOCK_SCREEN"] = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN; globalActions["TAKE_SCREENSHOT"] = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT }
                val action = globalActions[key]
                if (action != null && PhoneAccessibility.current != null) accepted(service().performGlobalAction(action))
                else { val code = key.toIntOrNull() ?: android.view.KeyEvent.keyCodeFromString(if (key.startsWith("KEYCODE_")) key else "KEYCODE_$key")
                    if (code <= 0 || code > android.view.KeyEvent.getMaxKeyCode()) fail("invalid_arguments", "不支持的按键")
                    privilege.execute(listOf("input", "keyevent", code.toString())) }
            }
            "phone_launch_app" -> {
                val pkg = packageName(a)
                val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: fail("not_found", "应用不可见或没有启动入口")
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                val confirmation = settleAppLaunch(pkg, agentId, epoch, deadline)
                JSONObject().put("accepted", true).put("verified", confirmation.optBoolean("verified"))
                    .put("package", pkg).put("launch_confirmation", confirmation)
            }
            "phone_wait" -> {
                waitFor(a, agentId, epoch, deadline)
            }
            "phone_list_apps" -> {
                val apps = context.packageManager.getInstalledApplications(0).filter { a.optBoolean("include_system") || it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }.map {
                    JSONObject().put("package_name", it.packageName).put("label", it.loadLabel(context.packageManager).toString()).put("uid", it.uid).put("enabled", it.enabled).put("apk_path", it.sourceDir) }
                JSONObject().put("apps", JSONArray(apps)).put("visibility", "system_granted")
            }
            "phone_transfer_file" -> transfer(a)
            "phone_shell" -> privilege.execute(listOf("sh", "-c", a.getString("command")), a.optString("backend", "auto"), a.optInt("timeout_ms", 10000).coerceIn(100, 30000))
            "phone_manage_app" -> {
                val action = a.getString("action")
                val cmd = when (action) {
                    "install" -> {
                        val file = exchange(a.getString("name")); if (!file.isFile) fail("not_found", "请先上传安装包")
                        return privilege.install(file, a.optString("backend", "auto"), (deadline - System.currentTimeMillis()).coerceIn(100, 120_000).toInt())
                    }
                    "force_stop" -> listOf("am", "force-stop", packageName(a))
                    "clear", "enable", "uninstall" -> listOf("pm", action, packageName(a))
                    "disable" -> listOf("pm", "disable-user", packageName(a))
                    else -> fail("invalid_arguments", "不支持的应用操作")
                }; privilege.execute(cmd, a.optString("backend", "auto"))
            }
            "phone_system_setting" -> {
                val ns = a.getString("namespace"); if (ns !in setOf("global", "secure", "system")) fail("invalid_arguments", "无效 namespace")
                val action = a.getString("action"); if (action !in setOf("get", "put", "delete")) fail("invalid_arguments", "无效 action")
                val key = a.getString("key"); if (!Regex("[a-zA-Z0-9_.-]{1,160}").matches(key)) fail("invalid_arguments", "无效 key")
                privilege.execute(listOf("settings", action, ns, key) + if (action == "put") listOf(a.getString("value")) else emptyList(), a.optString("backend", "auto"))
            }
            else -> PhoneAndroidActions(context).execute(name, a)
        }
    }
    private fun settleAppLaunch(pkg: String, owner: String, epoch: Long, deadline: Long): JSONObject {
        val accessibility = PhoneAccessibility.current
            ?: return JSONObject().put("status", "accessibility_unavailable").put("auto_approved", false).put("verified", false)
        val trusted = runCatching {
            val info = context.packageManager.getApplicationInfo(PhoneAppLaunch.SYSTEM_PACKAGE, 0)
            info.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
        if (!trusted) return JSONObject().put("status", "not_applicable").put("auto_approved", false).put("verified", false)
        val caller = context.applicationInfo.loadLabel(context.packageManager).toString()
        val target = context.packageManager.getApplicationInfo(pkg, 0).loadLabel(context.packageManager).toString()
        return PhoneAppLaunch.settle(pkg, caller, target, android.os.SystemClock::elapsedRealtime,
            checkActive = {
                connectionLog.requireOpen(owner)
                if (generation != epoch || !settings.enabled || PhoneAccessibility.current !== accessibility)
                    fail("cancelled", "手机操控已停止")
                if (System.currentTimeMillis() >= deadline) fail("timeout", "启动应用等待已超时")
                agentUi.requireUi(owner)
            },
            observe = { accessibility.observeAppLaunch(owner) },
            click = { snapshot, button -> accessibility.tap(JSONObject().put("snapshot_id", snapshot.getString("snapshot_id"))
                .put("node_id", button.getString("node_id")), owner) },
            pause = { snapshot, ms -> accessibility.awaitChange(snapshot.optLong("event_revision"), ms) })
    }
    private fun observe(owner: String, options: JSONObject = JSONObject()): JSONObject {
        PhoneExecution.validateObservation(options)
        val requestedLimit = options.optLong("limit", 400)
        val limit = requestedLimit.coerceIn(1, 600).toInt()
        val backend = options.optString("backend", "auto")
        val accessibility = PhoneAccessibility.current
        return when (backend) {
            "accessibility" -> (accessibility ?: fail("permission_required", "请启用无障碍服务，或显式选择 uiautomator")).observe(limit, owner, options)
            "uiautomator" -> privilegedObserve(limit, options)
            else -> accessibility?.observe(limit, owner, options) ?: privilegedObserve(limit, options)
        }.put("requested_limit", requestedLimit).put("effective_limit", limit)
    }
    private fun checkGuard(args: JSONObject, owner: String) {
        val guard = args.optJSONObject("guard") ?: return
        val data = observe(owner)
        if (guard.has("package_name") && data.optString("foreground_package", data.optString("package")) != guard.getString("package_name"))
            fail("precondition_failed", "前台应用与 guard 不符，本次动作未执行")
        guard.optJSONObject("selector")?.let { selector ->
            if (PhoneSelectors.rows(data).none { PhoneSelectors.matches(it, selector) }) fail("precondition_failed", "界面不满足 guard，本次动作未执行")
        }
    }
    private fun resolveSelector(args: JSONObject, owner: String): JSONObject {
        val selector = args.optJSONObject("selector") ?: return args
        if (PhoneAccessibility.current == null) fail("permission_required", "selector 操作需要无障碍服务")
        val state = observe(owner, JSONObject().put("mode", "compact"))
        val row = PhoneSelectors.unique(state, selector)
        return JSONObject(args.toString()).put("node_id", row.getString("node_id")).put("snapshot_id", state.getString("snapshot_id"))
    }
    private fun waitFor(condition: JSONObject, owner: String, epoch: Long, deadline: Long, before: JSONObject? = null): JSONObject {
        PhoneExecution.validateWait(condition)
        val end = minOf(deadline, System.currentTimeMillis() + condition.optLong("timeout_ms", 5000))
        var lastSignature = ""; var stableSince = android.os.SystemClock.elapsedRealtime()
        val begin = stableSince
        val baseline = before ?: if (condition.optBoolean("require_change")) observe(owner) else null
        val requireChange = condition.optBoolean("require_change", before != null)
        while (true) {
            connectionLog.requireOpen(owner)
            if (generation != epoch || !settings.enabled) fail("cancelled", "控制已停止")
            if (!control.tryLock()) {
                if (System.currentTimeMillis() >= end) fail("device_busy", "界面正在执行其他操作")
                Thread.sleep(25); continue
            }
            val data = try { agentUi.requireUi(owner); observe(owner) } finally { control.unlock() }
            val now = android.os.SystemClock.elapsedRealtime()
            val signature = data.optString("signature", data.getJSONArray("nodes").toString())
            if (signature != lastSignature) { stableSince = now; lastSignature = signature }
            val changed = baseline != null && PhoneExecution.changed(baseline, data)
            val matched = (!requireChange || changed) && PhoneExecution.matched(data, condition) && now - stableSince >= condition.optLong("stable_ms", 0)
            if (matched || System.currentTimeMillis() >= end) {
                condition.optJSONObject("selector")?.let { selector -> data.put("matched_nodes", JSONArray(PhoneSelectors.rows(data).filter { PhoneSelectors.matches(it, selector) })) }
                return data.put("matched", matched).put("wait_ms", now - begin).put("require_change", requireChange).put("state_changed", changed)
            }
            val stableRemaining = (condition.optLong("stable_ms", 0) - (now - stableSince)).takeIf { it > 0 } ?: 1000L
            val pause = minOf(stableRemaining, 1000, (end - System.currentTimeMillis()).coerceAtLeast(1))
            PhoneAccessibility.current?.awaitChange(data.optLong("event_revision"), pause)
                ?: Thread.sleep(minOf(250, pause))
        }
    }
    private fun batch(args: JSONObject, request: String, advanced: Boolean, epoch: Long, deadline: Long, owner: String, agent: String): JSONObject {
        val steps = PhoneExecution.validateBatch(args)
        val end = minOf(deadline, System.currentTimeMillis() + args.optLong("max_ms", 30000))
        val receipts = JSONArray(); var failure: String? = null; var completed = 0
        for ((index, step) in steps.withIndex()) {
            connectionLog.requireOpen(owner)
            if (generation != epoch || !settings.enabled) fail("cancelled", "控制已停止")
            if (System.currentTimeMillis() >= end) { failure = "batch_timeout"; break }
            val key = "step-" + MessageDigest.getInstance("SHA-256").digest("$request:$index".toByteArray()).joinToString("") { "%02x".format(it) }
            val response = invoke(step.first, step.second, key, advanced, end, owner, agent)
            val data = response.getJSONObject("structuredContent")
            val stepFailed = response.optBoolean("isError") || data.has("matched") && !data.optBoolean("matched")
            if (!response.optBoolean("isError") && stepFailed) data.put("code", "postcondition_timeout")
            val row = JSONObject().put("index", index).put("tool", step.first).put("operation_id", key).put("isError", stepFailed)
            for (field in listOf("code", "message", "accepted", "matched", "execution", "metrics")) if (data.has(field)) row.put(field, data.get(field))
            val observed = data.optJSONObject("state") ?: data.takeIf { it.has("nodes") }
            observed?.let { for (field in listOf("truncated", "visited_nodes", "backend")) if (it.has(field)) row.put(field, it.get(field)) }
            receipts.put(row)
            if (stepFailed) { failure = data.optString("code", "operation_failed"); break }
            completed++
        }
        val result = JSONObject().put("steps", receipts).put("completed_steps", completed).put("accepted", failure == null)
        if (failure != null) result.put("code", failure).put("resume_policy", "Inspect receipts and current state; do not replay a partial batch under a new ID")
        val mode = args.optString("return_state", "compact")
        if (mode != "none" && generation == epoch && settings.enabled && !connectionLog.blocked(owner)) {
            try { agentUi.requireUi(owner); result.put("state", observe(owner, JSONObject().put("mode", mode))) }
            catch (error: Exception) { result.put("state_error", error.message) }
        }
        return result
    }
    private fun packageName(a: JSONObject): String = a.getString("package_name").also { if (!Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+").matches(it)) fail("invalid_arguments", "无效包名") }
    private fun privilegedObserve(limit: Int, options: JSONObject = JSONObject()): JSONObject {
        val screen = PhoneScreen.info(context)
        val path = "/data/local/tmp/remotetool-ui-${java.util.UUID.randomUUID()}.xml"
        if (options.optBoolean("include_system")) fail("invalid_arguments", "include_system 仅支持 accessibility 后端")
        val compressed = if (options.optBoolean("include_unimportant", true)) "" else "--compressed "
        val result = privilege.execute(listOf("sh", "-c", "uiautomator dump $compressed'$path' >/dev/null && cat '$path'; code=\u0024?; rm -f '$path'; exit \u0024code"),
            options.optString("privilege_backend", "auto"), timeoutMs = 10000)
        if (result.optInt("exit_code", -1) != 0) fail("backend_unavailable", "特权 UI 树读取失败，可启用无障碍服务")
        return PhoneUiDump.parse(result.getString("stdout"), limit, options, screen, result.optString("backend"))
    }
    private fun exchange(name: String): File {
        if (name.isBlank() || name.length > 160 || name.contains('/') || name.contains('\\') || name == "." || name == "..") fail("invalid_arguments", "无效文件名")
        return File(File(context.filesDir, "phone-exchange").apply { mkdirs() }, name)
    }
    private fun transfer(a: JSONObject): JSONObject { return when (a.getString("action")) {
        "list" -> JSONObject().put("files", JSONArray(File(context.filesDir, "phone-exchange").listFiles().orEmpty().filter { it.isFile }.map { JSONObject().put("name", it.name).put("size", it.length()) }))
        "upload" -> {
            val file = exchange(a.getString("name")); val data = a.getString("data_base64")
            if (data.length > 2_800_000) fail("invalid_arguments", "文件超过 2 MiB")
            val bytes = Base64.decode(data, Base64.DEFAULT); if (bytes.size > 2 * 1024 * 1024) fail("invalid_arguments", "文件超过 2 MiB")
            if (a.optLong("offset", 0) < 0) fail("invalid_arguments", "无效文件偏移")
            if (a.has("offset") && a.getLong("offset") > 0) {
                if (!file.exists() || file.length() != a.getLong("offset")) fail("request_conflict", "文件偏移不匹配")
                file.appendBytes(bytes)
                return JSONObject().put("name", file.name).put("size", file.length())
            }
            val temp = File(file.parentFile, ".upload-${java.util.UUID.randomUUID()}")
            try { temp.writeBytes(bytes); java.nio.file.Files.move(temp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) } finally { temp.delete() }
            JSONObject().put("name", file.name).put("size", bytes.size)
        }
        "download" -> {
            val file = exchange(a.getString("name")); if (!file.isFile) fail("not_found", "文件不存在")
            val offset = a.optLong("offset", 0); if (offset < 0 || offset > file.length()) fail("invalid_arguments", "无效文件偏移")
            val bytes = java.io.RandomAccessFile(file, "r").use { input -> input.seek(offset); ByteArray(minOf(a.optInt("length", 2 * 1024 * 1024).coerceIn(1, 2 * 1024 * 1024).toLong(), file.length() - offset).toInt()).also { input.readFully(it) } }
            JSONObject().put("name", file.name).put("offset", offset).put("total", file.length()).put("data_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
        else -> fail("invalid_arguments", "action must be list/upload/download")
    } }
    companion object {
        @Volatile private var instance: PhoneEngine? = null
        fun get(context: Context): PhoneEngine = instance ?: synchronized(this) { instance ?: PhoneEngine(context.applicationContext).also { instance = it } }
        fun errorResult(error: Exception): JSONObject {
            val code = (error as? PhoneToolError)?.code ?: when (error) {
                is SecurityException -> "permission_required"
                is android.content.ActivityNotFoundException -> "not_found"
                is org.json.JSONException, is IllegalArgumentException -> "invalid_arguments"
                else -> "execution_failed"
            }
            val data = JSONObject().put("code", code).put("message", error.message ?: "执行失败")
            (error as? PhoneToolError)?.details?.let { data.put("details", it) }
            if (code in setOf("delivery_unknown", "connection_closed_by_user")) data.put("retryable", false)
            return JSONObject().put("isError", true).put("structuredContent", data).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString())))
        }
        private fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
            is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }
    }
}
