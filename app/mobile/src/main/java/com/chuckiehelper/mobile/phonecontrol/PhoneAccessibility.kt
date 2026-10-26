package com.chuckiehelper.mobile.phonecontrol

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneAccessibility : AccessibilityService() {
    override fun onServiceConnected() { current = this }
    override fun onDestroy() { if (current === this) current = null; clearSnapshots(); PhoneAccessibilityControl.request(this); super.onDestroy() }
    override fun onInterrupt() = Unit
    private val changes = Object()
    private val revisions = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null) synchronized(changes) { revisions.merge(event.windowId, 1L, Long::plus); changes.notifyAll() }
    }
    fun awaitChange(window: Int, revision: Long, milliseconds: Long) = synchronized(changes) {
        val duration = milliseconds.coerceIn(1, 1000)
        val started = System.nanoTime()
        var remaining = duration
        while ((revisions[window] ?: 0L) == revision && remaining > 0) {
            changes.wait(remaining)
            remaining = duration - (System.nanoTime() - started) / 1_000_000
        }
    }
    private data class Snapshot(val owner: String, val created: Long, val nodes: Map<String, AccessibilityNodeInfo>, val rows: Map<String, JSONObject>, val scope: String)
    private val snapshots = linkedMapOf<String, Snapshot>()
    @Synchronized fun clearSnapshots(owner: String? = null) {
        snapshots.entries.removeAll { entry ->
            (owner == null || entry.value.owner == owner).also { remove -> if (remove) entry.value.nodes.values.forEach { @Suppress("DEPRECATION") it.recycle() } }
        }
    }
    @Synchronized fun observe(limit: Int = 400, owner: String = "direct", options: JSONObject = JSONObject()): JSONObject {
        val now = android.os.SystemClock.elapsedRealtime()
        snapshots.entries.filter { now - it.value.created > 30_000 }.map { it.key }.forEach(::removeSnapshot)
        while (snapshots.values.count { it.owner == owner } >= 4) removeSnapshot(snapshots.entries.first { it.value.owner == owner }.key)
        while (snapshots.size >= 32) removeSnapshot(snapshots.keys.first())
        val nodes = linkedMapOf<String, AccessibilityNodeInfo>()
        val snapshot = UUID.randomUUID().toString()
        val rows = JSONArray()
        val info = PhoneScreen.info(this)
        val screen = Rect(0, 0, info.getInt("screen_width"), info.getInt("screen_height"))
        val mode = options.optString("mode", "compact")
        require(mode in setOf("compact", "text", "raw")) { "mode must be compact, text or raw" }
        val selector = options.optJSONObject("filter")?.also { PhoneSelectors.validate(it) }
        val contains = options.optJSONObject("_find")
        var occluders: List<Rect> = emptyList()
        var visited = 0; var truncated = false
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (rows.length() >= limit || visited >= 2400 || depth > 35) { truncated = true; return }
            visited++
            val id = "n${nodes.size + 1}"; val rect = Rect(); node.getBoundsInScreen(rect)
            val visible = PhoneScreen.visible(rect, screen)
            val row = JSONObject().put("node_id", id).put("depth", depth).put("text", if (node.isPassword) "" else node.text?.toString().orEmpty())
                .put("description", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty()).put("resource_id", node.viewIdResourceName.orEmpty())
                .put("class", node.className?.toString()).put("clickable", node.isClickable).put("editable", node.isEditable)
                .put("focused", node.isFocused).put("visible", node.isVisibleToUser).put("password", node.isPassword)
                .put("window_id", node.windowId).put("package_name", node.packageName?.toString().orEmpty()).put("enabled", node.isEnabled)
                .put("raw_bounds", PhoneScreen.json(rect)).put("bounds", PhoneScreen.json(visible ?: Rect()))
                .put("bounds_valid", visible != null).put("actions", JSONArray(node.actionList.map { it.id }))
            val tap = visible?.let { PhoneScreen.tapArea(it, occluders) }
            row.put("occluded", visible != null && tap == null)
            if (tap != null) row.put("tap_point", JSONObject().put("x", tap.centerX()).put("y", tap.centerY()))
            val useful = row.optString("text").isNotBlank() || row.optString("description").isNotBlank() || node.isClickable || node.isEditable || node.isScrollable
            if ((mode == "raw" || node.isVisibleToUser && visible != null && useful) && (selector == null || PhoneSelectors.matches(row, selector)) &&
                (contains == null || contains.keys().asSequence().all { row.optString(it).contains(contains.getString(it), true) })) {
                @Suppress("DEPRECATION") val copy = AccessibilityNodeInfo.obtain(node)
                nodes[id] = copy; rows.put(row)
            }
            for (i in 0 until node.childCount) {
                if (rows.length() >= limit || visited >= 2400) { truncated = true; break }
                val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        val applications = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .sortedWith(compareByDescending<AccessibilityWindowInfo> { it.isFocused }.thenByDescending { it.isActive }.thenByDescending { it.layer })
        val foreground = applications.firstOrNull()
        val selected = options.optString("package_name").takeIf { it.isNotBlank() }?.let { target ->
            applications.firstOrNull { window -> window.root?.let { root -> try { root.packageName?.toString() == target } finally { @Suppress("DEPRECATION") root.recycle() } } == true }
                ?: fail("window_not_found", "目标应用窗口不可见")
        } ?: foreground
        occluders = if (selected != null && !options.optBoolean("include_system")) windows.filter { it.layer > selected.layer }
            .map { Rect().also(it::getBoundsInScreen) }.filter { it.width() > 0 && it.height() > 0 } else emptyList()
        val roots = if (options.optBoolean("include_system")) windows.mapNotNull { it.root }
            else listOfNotNull(selected?.root ?: rootInActiveWindow)
        val pkg = roots.firstOrNull()?.packageName?.toString().orEmpty()
        val foregroundPackage = foreground?.root?.let { root -> try { root.packageName?.toString() } finally { @Suppress("DEPRECATION") root.recycle() } }.orEmpty()
        roots.forEach { try { visit(it, 0) } finally { @Suppress("DEPRECATION") it.recycle() } }
        val scope = JSONObject(options.toString()).apply { remove("changed_since"); remove("screenshot"); put("selected_window", selected?.id ?: -1); put("selected_package", pkg) }.toString()
        val old = snapshots[options.optString("changed_since")]?.takeIf { it.owner == owner && it.scope == scope }
        val indexed = (0 until rows.length()).associate { rows.getJSONObject(it).getString("node_id") to rows.getJSONObject(it) }
        snapshots[snapshot] = Snapshot(owner, now, nodes, indexed, scope)
        val signature = java.security.MessageDigest.getInstance("SHA-256").digest(rows.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        val result = info.put("snapshot_id", snapshot).put("package", pkg).put("foreground_package", foregroundPackage.ifBlank { pkg }).put("nodes", rows)
            .put("window_id", selected?.id ?: -1).put("revision", revisions[selected?.id ?: -1] ?: 0L).put("signature", signature)
            .put("mode", mode).put("truncated", truncated).put("visited_nodes", visited)
            .apply { if (mode == "text") put("text", (0 until rows.length()).map { rows.getJSONObject(it) }.flatMap { listOf(it.optString("text"), it.optString("description")) }.filter { it.isNotBlank() }.distinct().joinToString("\n")) }
        if (options.has("changed_since")) {
            result.put("delta", old != null).put("reset", old == null)
            if (old != null) {
                result.put("nodes", JSONArray(indexed.values.filter { old.rows[it.getString("node_id")]?.toString() != it.toString() }))
                    .put("removed_nodes", JSONArray(old.rows.keys.filter { it !in indexed })).put("base_snapshot_id", options.getString("changed_since"))
            }
        }
        options.optJSONArray("fields")?.let { requested ->
            val allowed = setOf("node_id", "window_id", "package_name", "depth", "text", "description", "resource_id", "class", "clickable", "editable", "focused", "visible", "password", "enabled", "bounds", "raw_bounds", "bounds_valid", "occluded", "actions", "tap_point")
            val keep = (0 until requested.length()).map { requested.getString(it).also { field -> require(field in allowed) { "Unknown observation field: $field" } } }.toSet() + setOf("node_id", "window_id", "bounds", "bounds_valid", "visible", "tap_point")
            result.put("nodes", JSONArray((0 until result.getJSONArray("nodes").length()).map { i ->
                val row = result.getJSONArray("nodes").getJSONObject(i)
                JSONObject().apply { row.keys().forEach { key -> if (key in keep) put(key, row.get(key)) } }
            }))
        }
        return result
    }
    private fun removeSnapshot(id: String) { snapshots.remove(id)?.nodes?.values?.forEach { @Suppress("DEPRECATION") it.recycle() } }
    @Synchronized fun node(id: String, snapshotId: String, owner: String = "direct"): AccessibilityNodeInfo {
        val snapshot = snapshots[snapshotId]
        if (snapshot == null || snapshot.owner != owner || android.os.SystemClock.elapsedRealtime() - snapshot.created > 30_000)
            fail("stale_snapshot", "请重新 phone_observe 获取节点")
        val node = snapshot.nodes[id] ?: fail("node_not_found", "节点不存在")
        if (!node.refresh()) fail("stale_snapshot", "节点已失效")
        val previous = snapshot.rows[id]!!
        if (previous.optString("resource_id") != node.viewIdResourceName.orEmpty() ||
            !node.isPassword && (previous.optString("text") != node.text?.toString().orEmpty() || previous.optString("description") != node.contentDescription?.toString().orEmpty()))
            fail("stale_snapshot", "节点内容已变化，请重新观察")
        return node
    }
    @Synchronized fun tap(args: JSONObject, owner: String = "direct"): Boolean {
        if (args.has("node_id")) {
            val target = node(args.getString("node_id"), args.getString("snapshot_id"), owner)
            val rect = Rect(); target.getBoundsInScreen(rect)
            val screen = PhoneScreen.info(this)
            if (!target.isVisibleToUser || !target.isEnabled || PhoneScreen.visible(rect, Rect(0, 0, screen.getInt("screen_width"), screen.getInt("screen_height"))) == null)
                fail("target_not_visible", "目标没有有效可见区域，请先滚动或重新观察")
            val targetWindow = windows.firstOrNull { it.id == target.windowId }
            val blockers = if (targetWindow != null) windows.filter { it.layer > targetWindow.layer }.map { Rect().also(it::getBoundsInScreen) } else emptyList()
            if (PhoneScreen.tapArea(PhoneScreen.visible(rect, Rect(0, 0, screen.getInt("screen_width"), screen.getInt("screen_height")))!!, blockers) == null)
                fail("target_obscured", "目标被其他窗口完全遮挡，请先处理遮挡窗口")
            val action = if (args.optBoolean("long_press")) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            if (if (args.optBoolean("long_press")) target.isLongClickable else target.isClickable) return target.performAction(action)
            var parent = target.parent
            var depth = 0
            while (parent != null && depth++ < 8) {
                val active = parent
                try {
                    val bounds = Rect(); active.getBoundsInScreen(bounds)
                    if (active.windowId != target.windowId || !bounds.contains(rect)) break
                    if (active.isVisibleToUser && active.isEnabled && if (args.optBoolean("long_press")) active.isLongClickable else active.isClickable)
                        return active.performAction(action)
                    parent = active.parent
                } finally { @Suppress("DEPRECATION") active.recycle() }
            }
            fail("node_not_actionable", "目标及其可见祖先不支持此点击动作，请使用其他选择器或截图核对")
        }
        return gesture(args.getDouble("x").toFloat(), args.getDouble("y").toFloat(), args.getDouble("x").toFloat(), args.getDouble("y").toFloat(), if (args.optBoolean("long_press")) 700 else 80)
    }
    @Synchronized fun input(args: JSONObject, owner: String = "direct"): Boolean {
        val target = if (args.has("node_id")) node(args.getString("node_id"), args.getString("snapshot_id"), owner)
            else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: fail("node_not_found", "没有可输入的焦点节点")
        if (!target.isVisibleToUser || !target.isEnabled || !target.isEditable) fail("node_not_actionable", "目标不是可见、启用的输入控件")
        val text = args.getString("text")
        val value = if (args.optBoolean("append")) target.text?.toString().orEmpty() + text else text
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
    }
    @Synchronized fun uiAction(args: JSONObject, owner: String = "direct"): Boolean {
        val actions = mapOf("focus" to AccessibilityNodeInfo.ACTION_FOCUS, "clear_focus" to AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,
            "select" to AccessibilityNodeInfo.ACTION_SELECT, "clear_selection" to AccessibilityNodeInfo.ACTION_CLEAR_SELECTION,
            "scroll_forward" to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scroll_backward" to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            "expand" to AccessibilityNodeInfo.ACTION_EXPAND, "collapse" to AccessibilityNodeInfo.ACTION_COLLAPSE,
            "copy" to AccessibilityNodeInfo.ACTION_COPY, "cut" to AccessibilityNodeInfo.ACTION_CUT, "paste" to AccessibilityNodeInfo.ACTION_PASTE,
            "accessibility_focus" to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, "dismiss" to AccessibilityNodeInfo.ACTION_DISMISS,
            "set_selection" to AccessibilityNodeInfo.ACTION_SET_SELECTION)
        val action = actions[args.getString("action")] ?: args.getString("action").toIntOrNull() ?: fail("invalid_arguments", "Unsupported node action")
        val bundle = Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, args.optInt("start")); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, args.optInt("end")) }
        return node(args.getString("node_id"), args.getString("snapshot_id"), owner).performAction(action, bundle)
    }
    fun multiGesture(args: JSONObject): Boolean {
        val strokes = args.getJSONArray("strokes"); if (strokes.length() !in 1..10) fail("invalid_arguments", "1-10 gesture strokes required")
        val builder = GestureDescription.Builder()
        for (i in 0 until strokes.length()) {
            val stroke = strokes.getJSONObject(i); val points = stroke.getJSONArray("points")
            if (points.length() !in 1..100) fail("invalid_arguments", "1-100 points per stroke required")
            val path = Path()
            for (j in 0 until points.length()) { val p = points.getJSONArray(j); val x = p.getDouble(0).toFloat(); val y = p.getDouble(1).toFloat()
                if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0) fail("invalid_arguments", "Invalid coordinates")
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            builder.addStroke(GestureDescription.StrokeDescription(path, stroke.optLong("start_ms", 0).coerceIn(0, 1000), stroke.optLong("duration_ms", 400).coerceIn(1, 3000)))
        }
        val done = CountDownLatch(1); var ok = false
        Handler(Looper.getMainLooper()).post {
            try {
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription?) {
                        ok = true
                        done.countDown()
                    }
                    override fun onCancelled(gesture: GestureDescription?) { done.countDown() }
                }
                if (!dispatchGesture(builder.build(), callback, null)) done.countDown()
            } catch (_: Exception) { done.countDown() }
        }
        if (!done.await(5, TimeUnit.SECONDS)) fail("delivery_unknown", "手势回执超时，请观察核对")
        return ok
    }
    fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): Boolean {
        if (listOf(x1, y1, x2, y2).any { !it.isFinite() || it < 0 }) fail("invalid_arguments", "无效屏幕坐标")
        val screen = PhoneScreen.info(this)
        if (maxOf(x1, x2) >= screen.getInt("screen_width") || maxOf(y1, y2) >= screen.getInt("screen_height")) fail("invalid_arguments", "坐标超出物理屏幕范围")
        val done = CountDownLatch(1); var ok = false
        Handler(Looper.getMainLooper()).post {
            try {
                val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
                val callback = object : GestureResultCallback() {
                    override fun onCompleted(gesture: GestureDescription?) {
                        ok = true
                        done.countDown()
                    }
                    override fun onCancelled(gesture: GestureDescription?) { done.countDown() }
                }
                val description = GestureDescription.Builder().addStroke(
                    GestureDescription.StrokeDescription(path, 0, duration.coerceIn(1, 3000))
                ).build()
                if (!dispatchGesture(description, callback, null)) done.countDown()
            } catch (_: Exception) { done.countDown() }
        }
        if (!done.await(5, TimeUnit.SECONDS)) fail("delivery_unknown", "手势回执超时，请观察后核对")
        return ok
    }
    fun screenshot(): ByteArray {
        if (Build.VERSION.SDK_INT >= 30) return screenshot30()
        fail("backend_unavailable", "无障碍截图需要 Android 11+")
    }
    @androidx.annotation.RequiresApi(30)
    private fun screenshot30(): ByteArray {
        val done = CountDownLatch(1); var result: ByteArray? = null; var error = -1
        takeScreenshot(0, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(shot: ScreenshotResult) {
                try {
                    val wrapped = Bitmap.wrapHardwareBuffer(shot.hardwareBuffer, shot.colorSpace)
                    val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped?.recycle()
                    if (bitmap != null) { val scale = minOf(1.0, 1600.0 / maxOf(bitmap.width, bitmap.height))
                        val small = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
                        result = java.io.ByteArrayOutputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it); it.toByteArray() }
                        if (small !== bitmap) small.recycle(); bitmap.recycle()
                    }
                } catch (_: Exception) { error = -1 }
                finally { shot.hardwareBuffer.close(); done.countDown() }
            }
            override fun onFailure(code: Int) { error = code; done.countDown() }
        })
        if (!done.await(8, TimeUnit.SECONDS)) fail("timeout", "截图超时")
        return result ?: fail("screenshot_failed", "系统拒绝截图：$error（受保护窗口可能不可截图）")
    }
    companion object {
        @Volatile var current: PhoneAccessibility? = null; private set
        fun requireService() = current ?: fail("permission_required", "请在系统设置中启用 RemoteTool 无障碍服务")
    }
}
