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
    private val control = ReentrantLock()
    private val assistance = ReentrantLock()
    @Volatile var generation = 0L; private set
    fun stop() { generation++; settings.enabled = false; privilege.close() }
    fun capabilities(): JSONObject = settings.description().put("schema_version", PhoneToolCatalog.VERSION)
        .put("advanced", settings.advanced).put("accessibility", PhoneAccessibility.current != null)
        .put("notification_access", PhoneNotifications.current != null).put("tool_count", PhoneToolCatalog.tools(settings.advanced).length())
        .put("privilege", privilege.status()).put("android_api", android.os.Build.VERSION.SDK_INT)
        .put("agent_display", agentUi.capabilities())
        .put("screen_width", context.resources.displayMetrics.widthPixels).put("screen_height", context.resources.displayMetrics.heightPixels)
    fun status(id: String) = journal.status(id)
    fun invoke(name: String, arguments: JSONObject, requestId: String, allowAdvanced: Boolean = true,
        deadline: Long = System.currentTimeMillis() + 35_000, agentId: String = "direct", agentName: String = "Agent"): JSONObject {
        try {
            if (!settings.enabled) fail("disabled", "手机操控未开启")
            if (!Regex("[a-zA-Z0-9_.:-]{1,160}").matches(requestId)) fail("invalid_arguments", "Invalid request_id")
            PhoneToolCatalog.validate(name, arguments)
            if (name in PhoneToolCatalog.advanced && (!settings.advanced || !allowAdvanced)) fail("advanced_disabled", "高级操作未开启")
            val digest = MessageDigest.getInstance("SHA-256").digest((agentId + ":" + name + ":" + allowAdvanced + ":" + canonical(arguments)).toByteArray()).joinToString("") { "%02x".format(it) }
            journal.existing(requestId, digest)?.let { return it }
            // Assistance and metadata must stay available even while an Android UI
            // binder call is slow. They never execute arbitrary device mutations.
            val operationLock = if (name in PhoneToolCatalog.responsive) assistance else control
            if (!operationLock.tryLock()) fail("device_busy", "手机正在执行其他操作，请稍后重试")
            try {
                if (!settings.enabled || deadline < System.currentTimeMillis()) fail("timeout", "请求已过期或控制已停止")
                journal.existing(requestId, digest)?.let { return it }
                journal.begin(requestId, digest)
                agentUi.seen(agentId, agentName)
                val epoch = generation
                agentUi.toolStarted(agentId, name)
                val result = try {
                    val data = execute(name, arguments, epoch, deadline, agentId)
                    val failed = data.has("exit_code") && data.optInt("exit_code", -1) != 0 || data.optBoolean("timed_out") || data.has("accepted") && !data.optBoolean("accepted")
                    if (failed) data.put("code", if (data.optBoolean("timed_out")) "delivery_unknown" else "operation_failed")
                    JSONObject().put("isError", failed).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", data.toString())))
                        .put("structuredContent", data)
                } catch (error: Exception) { errorResult(error) }
                finally { agentUi.toolFinished(agentId, name) }
                // Large image data is carried separately from structured metadata.
                result.optJSONObject("structuredContent")?.optString("image_base64")?.takeIf { it.isNotBlank() }?.let { image ->
                    val data = result.getJSONObject("structuredContent"); val mime = data.optString("image_mime", "image/jpeg")
                    val imageBytes = Base64.decode(image, Base64.DEFAULT)
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
                    data.put("image_width", bounds.outWidth).put("image_height", bounds.outHeight)
                    data.remove("image_base64"); result.getJSONArray("content").getJSONObject(0).put("text", data.toString())
                    result.getJSONArray("content").put(JSONObject().put("type", "image").put("mimeType", mime).put("data", image))
                }
                journal.finish(requestId, result)
                return result
            } finally { operationLock.unlock() }
        } catch (error: Exception) { return errorResult(error) }
    }
    private fun execute(name: String, a: JSONObject, epoch: Long, deadline: Long, agentId: String): JSONObject {
        fun accepted(ok: Boolean) = JSONObject().put("accepted", ok).put("verified", false).put("backend", "accessibility")
        fun service() = PhoneAccessibility.requireService()
        return when (name) {
            "phone_disconnect" -> {
                agentUi.disconnected(agentId)
                JSONObject().put("disconnected", true).put("service_stopped", false)
            }
            "phone_begin_ui" -> {
                val duration = a.optInt("duration_seconds", 300).coerceIn(15, 600)
                agentUi.beginUi(agentId, a.optString("message", "正在操作屏幕"), duration)
                agentUi.capabilities().put("declared", true).put("expires_in", duration)
            }
            "phone_end_ui" -> {
                agentUi.endUi(agentId)
                a.optString("message").takeIf { it.isNotBlank() }?.let { agentUi.notify(agentId, it, false, 10) }
                JSONObject().put("declared", false)
            }
            "phone_notify_user" -> {
                val kind = a.optString("kind", "info")
                if (kind !in setOf("info", "help") || a.getString("message").isBlank() || a.getString("message").length > 1000)
                    fail("invalid_arguments", "kind 必须为 info/help，message 长度为 1–1000")
                agentUi.notify(agentId, a.getString("message"), kind == "help", a.optInt("timeout_seconds", if (kind == "help") 600 else 15))
            }
            "phone_get_user_reply" -> agentUi.result(agentId, a.getString("notice_id"))
            "phone_list_devices" -> JSONObject().put("devices", JSONArray().put(settings.description()))
            "phone_get_capabilities" -> capabilities()
            "phone_observe", "phone_find_nodes" -> {
                val data = if (PhoneAccessibility.current != null) service().observe(a.optInt("limit", 400).coerceIn(1, 600)) else privilegedObserve(a.optInt("limit", 400).coerceIn(1, 600))
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
            "phone_tap" -> if (PhoneAccessibility.current != null) accepted(service().tap(a)) else {
                if (a.has("node_id") || a.optBoolean("long_press")) fail("permission_required", "节点/长按操作需要无障碍服务")
                privilege.execute(listOf("input", "tap", a.getInt("x").toString(), a.getInt("y").toString()))
            }
            "phone_swipe" -> if (PhoneAccessibility.current != null) accepted(service().gesture(a.getDouble("x1").toFloat(), a.getDouble("y1").toFloat(), a.getDouble("x2").toFloat(), a.getDouble("y2").toFloat(), a.optLong("duration_ms", 400)))
                else privilege.execute(listOf("input", "swipe") + listOf("x1", "y1", "x2", "y2").map { a.getInt(it).toString() } + a.optInt("duration_ms", 400).coerceIn(1, 3000).toString())
            "phone_input_text" -> if (PhoneAccessibility.current != null) accepted(service().input(a)) else {
                if (a.has("node_id") || a.getString("text").any { it.code !in 32..126 }) fail("permission_required", "Unicode/节点输入需要无障碍服务；也可通过特权 Shell 配合设备输入法")
                privilege.execute(listOf("input", "text", a.getString("text").replace(" ", "%s")))
            }
            "phone_ui_action" -> accepted(service().uiAction(a))
            "phone_gesture" -> accepted(service().multiGesture(a))
            "phone_screenshot" -> {
                val backend = a.optString("backend", "auto")
                if (backend !in setOf("auto", "accessibility", "shizuku", "root")) fail("invalid_arguments", "Invalid screenshot backend")
                val accessibility = backend == "accessibility" || backend == "auto" && PhoneAccessibility.current != null && android.os.Build.VERSION.SDK_INT >= 30
                val bytes = if (accessibility) service().screenshot() else privilege.screenshot(backend)
                JSONObject().put("image_base64", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("image_mime", "image/jpeg").put("coordinate_space", "original_screen_pixels")
                    .put("screen_width", context.resources.displayMetrics.widthPixels).put("screen_height", context.resources.displayMetrics.heightPixels)
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
                JSONObject().put("accepted", true).put("verified", false).put("package", pkg)
            }
            "phone_wait" -> {
                if (!a.has("text") && !a.has("package_name")) fail("invalid_arguments", "需要 text 或 package_name")
                val end = minOf(deadline, System.currentTimeMillis() + a.optLong("timeout_ms", 5000).coerceIn(1, 10000))
                var data: JSONObject
                while (true) {
                    if (generation != epoch || !settings.enabled) fail("cancelled", "控制已停止")
                    data = if (PhoneAccessibility.current != null) service().observe() else privilegedObserve(400)
                    val matched = (!a.has("package_name") || data.optString("package") == a.getString("package_name")) &&
                        (!a.has("text") || data.getJSONArray("nodes").toString().contains(a.getString("text")))
                    if (matched) { data.put("matched", true); break }
                    if (System.currentTimeMillis() >= end) { data.put("matched", false); break }
                    Thread.sleep(250)
                }; data
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
                        val temp = "/data/local/tmp/remotetool-${java.util.UUID.randomUUID()}.apk"
                        try {
                            file.inputStream().use { input -> val buffer = ByteArray(24 * 1024); while (true) {
                                if (generation != epoch || !settings.enabled || System.currentTimeMillis() > deadline) fail("cancelled", "安装包传输已停止")
                                val n = input.read(buffer); if (n < 0) break
                                val encoded = Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP)
                                val chunk = privilege.execute(listOf("sh", "-c", "printf '%s' '$encoded' | base64 -d >> '$temp'"), a.optString("backend", "auto"))
                                if (chunk.optInt("exit_code", -1) != 0) fail("execution_failed", "安装包暂存失败")
                            } }
                        } catch (e: Exception) { runCatching { privilege.execute(listOf("rm", "-f", temp), a.optString("backend", "auto")) }; throw e }
                        listOf("sh", "-c", "pm install -r '$temp'; code=\u0024?; rm -f '$temp'; exit \u0024code")
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
    private fun packageName(a: JSONObject): String = a.getString("package_name").also { if (!Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+").matches(it)) fail("invalid_arguments", "无效包名") }
    private fun privilegedObserve(limit: Int): JSONObject {
        val path = "/data/local/tmp/remotetool-ui-${java.util.UUID.randomUUID()}.xml"
        val result = privilege.execute(listOf("sh", "-c", "uiautomator dump --compressed '$path' >/dev/null && cat '$path'; code=\u0024?; rm -f '$path'; exit \u0024code"), timeoutMs = 10000)
        if (result.optInt("exit_code", -1) != 0) fail("backend_unavailable", "特权 UI 树读取失败，可启用无障碍服务")
        val parser = android.util.Xml.newPullParser(); parser.setInput(java.io.StringReader(result.getString("stdout")))
        val nodes = JSONArray(); var pkg = ""
        while (parser.eventType != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "node" && nodes.length() < limit) {
                fun attr(key: String) = parser.getAttributeValue(null, key).orEmpty()
                if (pkg.isEmpty()) pkg = attr("package")
                val bounds = Regex("-?\\d+").findAll(attr("bounds")).map { it.value.toInt() }.toList()
                nodes.put(JSONObject().put("node_id", "p${nodes.length() + 1}").put("text", attr("text")).put("description", attr("content-desc"))
                    .put("resource_id", attr("resource-id")).put("bounds", JSONArray(bounds)).put("clickable", attr("clickable") == "true").put("depth", parser.depth))
            }; parser.next()
        }
        return JSONObject().put("snapshot_id", java.util.UUID.randomUUID().toString()).put("package", pkg).put("nodes", nodes)
            .put("backend", result.optString("backend")).put("node_actions", false).put("instruction", "Use bounds and coordinate taps for privileged UI snapshots; node IDs require accessibility.")
            .put("screen_width", context.resources.displayMetrics.widthPixels).put("screen_height", context.resources.displayMetrics.heightPixels)
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
