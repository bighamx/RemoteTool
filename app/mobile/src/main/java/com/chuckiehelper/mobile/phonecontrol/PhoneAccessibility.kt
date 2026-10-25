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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneAccessibility : AccessibilityService() {
    override fun onServiceConnected() { current = this }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
    override fun onInterrupt() = Unit
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    private val nodes = linkedMapOf<String, AccessibilityNodeInfo>()
    private var snapshot = ""
    private var observedAt = 0L
    @Synchronized fun observe(limit: Int = 400): JSONObject {
        nodes.values.forEach { @Suppress("DEPRECATION") it.recycle() }; nodes.clear()
        snapshot = UUID.randomUUID().toString(); observedAt = android.os.SystemClock.elapsedRealtime()
        val rows = JSONArray()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (rows.length() >= limit || depth > 35) return
            val id = "n${nodes.size + 1}"; val rect = Rect(); node.getBoundsInScreen(rect)
            @Suppress("DEPRECATION") val copy = AccessibilityNodeInfo.obtain(node)
            nodes[id] = copy
            rows.put(JSONObject().put("node_id", id).put("depth", depth).put("text", if (node.isPassword) "" else node.text?.toString().orEmpty())
                .put("description", if (node.isPassword) "" else node.contentDescription?.toString().orEmpty()).put("resource_id", node.viewIdResourceName.orEmpty())
                .put("class", node.className?.toString()).put("clickable", node.isClickable).put("editable", node.isEditable)
                .put("focused", node.isFocused).put("visible", node.isVisibleToUser).put("password", node.isPassword)
                .put("bounds", JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))))
            for (i in 0 until node.childCount) { val child = node.getChild(i) ?: continue
                try { visit(child, depth + 1) } finally { @Suppress("DEPRECATION") child.recycle() }
            }
        }
        val roots = windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }
        val pkg = rootInActiveWindow?.packageName?.toString().orEmpty()
        roots.forEach { try { visit(it, 0) } finally { @Suppress("DEPRECATION") it.recycle() } }
        val dm = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") val display = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay
        @Suppress("DEPRECATION") display.getRealMetrics(dm)
        return JSONObject().put("snapshot_id", snapshot).put("package", pkg).put("nodes", rows)
            .put("screen_width", dm.widthPixels).put("screen_height", dm.heightPixels).put("rotation", display.rotation * 90).put("coordinate_space", "screen_pixels")
    }
    @Synchronized fun node(id: String, snapshotId: String): AccessibilityNodeInfo {
        if (snapshotId != snapshot || android.os.SystemClock.elapsedRealtime() - observedAt > 30_000)
            fail("stale_snapshot", "请重新 phone_observe 获取节点")
        val node = nodes[id] ?: fail("node_not_found", "节点不存在")
        if (!node.refresh()) fail("stale_snapshot", "节点已失效")
        return node
    }
    fun tap(args: JSONObject): Boolean {
        if (args.has("node_id")) return node(args.getString("node_id"), args.getString("snapshot_id"))
            .performAction(if (args.optBoolean("long_press")) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK)
        return gesture(args.getDouble("x").toFloat(), args.getDouble("y").toFloat(), args.getDouble("x").toFloat(), args.getDouble("y").toFloat(), if (args.optBoolean("long_press")) 700 else 80)
    }
    fun input(args: JSONObject): Boolean {
        val target = if (args.has("node_id")) node(args.getString("node_id"), args.getString("snapshot_id"))
            else rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: fail("node_not_found", "没有可输入的焦点节点")
        val text = args.getString("text")
        val value = if (args.optBoolean("append")) target.text?.toString().orEmpty() + text else text
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) })
    }
    fun uiAction(args: JSONObject): Boolean {
        val actions = mapOf("focus" to AccessibilityNodeInfo.ACTION_FOCUS, "clear_focus" to AccessibilityNodeInfo.ACTION_CLEAR_FOCUS,
            "select" to AccessibilityNodeInfo.ACTION_SELECT, "clear_selection" to AccessibilityNodeInfo.ACTION_CLEAR_SELECTION,
            "scroll_forward" to AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, "scroll_backward" to AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD,
            "expand" to AccessibilityNodeInfo.ACTION_EXPAND, "collapse" to AccessibilityNodeInfo.ACTION_COLLAPSE,
            "copy" to AccessibilityNodeInfo.ACTION_COPY, "cut" to AccessibilityNodeInfo.ACTION_CUT, "paste" to AccessibilityNodeInfo.ACTION_PASTE,
            "accessibility_focus" to AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, "dismiss" to AccessibilityNodeInfo.ACTION_DISMISS,
            "set_selection" to AccessibilityNodeInfo.ACTION_SET_SELECTION)
        val action = actions[args.getString("action")] ?: args.getString("action").toIntOrNull() ?: fail("invalid_arguments", "Unsupported node action")
        val bundle = Bundle().apply { putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, args.optInt("start")); putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, args.optInt("end")) }
        return node(args.getString("node_id"), args.getString("snapshot_id")).performAction(action, bundle)
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
