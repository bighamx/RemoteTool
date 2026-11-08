package com.chuckiehelper.mobile.phonecontrol

import android.content.Context
import android.graphics.Rect
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject

internal object PhoneScreen {
    @Suppress("DEPRECATION")
    fun info(context: Context): JSONObject {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val metrics = DisplayMetrics(); display.getRealMetrics(metrics)
        return JSONObject().put("screen_width", metrics.widthPixels).put("screen_height", metrics.heightPixels)
            .put("rotation", display.rotation * 90).put("coordinate_space", "screen_pixels")
    }
    fun visible(raw: Rect, viewport: Rect): Rect? = if (raw.width() > 0 && raw.height() > 0)
        Rect(raw).takeIf { it.intersect(viewport) && it.width() > 0 && it.height() > 0 } else null
    fun json(rect: Rect) = JSONArray(listOf(rect.left, rect.top, rect.right, rect.bottom))
    fun tapArea(visible: Rect, obstacles: List<Rect>): Rect? {
        if (obstacles.size > 32) return null
        var pieces = listOf(Rect(visible))
        for (obstacle in obstacles.take(32)) {
            pieces = pieces.flatMap { area ->
                val intersection = Rect(area)
                if (!intersection.intersect(obstacle)) listOf(area) else listOf(
                    Rect(area.left, area.top, intersection.left, area.bottom), Rect(intersection.right, area.top, area.right, area.bottom),
                    Rect(intersection.left, area.top, intersection.right, intersection.top), Rect(intersection.left, intersection.bottom, intersection.right, area.bottom)
                ).filter { it.width() >= 2 && it.height() >= 2 }
            }.sortedByDescending { it.width().toLong() * it.height() }.take(64)
        }
        return pieces.maxByOrNull { it.width().toLong() * it.height() }
    }
}

/** Shared selector semantics for observe, wait and actions. No scripts or arbitrary predicates. */
internal object PhoneSelectors {
    private val strings = setOf("text", "text_contains", "text_regex", "description", "resource_id", "class", "package_name")
    fun validate(selector: JSONObject) {
        require(selector.length() > 0) { "selector must contain a condition" }
        for (key in selector.keys()) {
            when (key) {
                in strings -> require(selector.get(key) is String && selector.getString(key).length <= 512) { "Invalid selector $key" }
                "clickable", "editable", "visible" -> require(selector.get(key) is Boolean) { "Invalid selector $key" }
                "unique" -> require(selector.get(key) == true) { "Actions always require a unique target" }
                else -> fail("invalid_arguments", "Unknown selector field: $key")
            }
        }
        selector.optString("text_regex").takeIf { it.isNotEmpty() }?.let {
            require(it.length <= 128) { "text_regex too long" }; Regex(it)
        }
    }
    fun matches(row: JSONObject, selector: JSONObject): Boolean {
        for (key in selector.keys()) {
            val value = selector.get(key)
            val matched = when (key) {
                "unique" -> true
                "text_contains" -> row.optString("text").contains(value.toString(), true) || row.optString("description").contains(value.toString(), true)
                "text_regex" -> Regex(value.toString(), RegexOption.IGNORE_CASE).containsMatchIn(row.optString("text") + "\n" + row.optString("description"))
                "clickable", "editable", "visible" -> row.optBoolean(key) == value
                else -> row.optString(key) == value
            }
            if (!matched) return false
        }
        return true
    }
    fun rows(data: JSONObject): List<JSONObject> = data.getJSONArray("nodes").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) } }
    fun unique(data: JSONObject, selector: JSONObject): JSONObject {
        validate(selector)
        val candidates = rows(data).filter { matches(it, selector) }
        val matches = candidates.filter { it.optBoolean("visible") && it.optBoolean("bounds_valid") && !it.optBoolean("occluded") }
        if (matches.isEmpty()) {
            val details = JSONObject().put("candidates_found", candidates.size)
                .put("visible_candidates", candidates.count { it.optBoolean("visible") })
                .put("bounds_valid_candidates", candidates.count { it.optBoolean("bounds_valid") })
                .put("occluded_candidates", candidates.count { it.optBoolean("occluded") })
            if (candidates.isEmpty()) {
                details.put("reason", "not_in_snapshot")
                throw PhoneToolError("node_not_found", "当前快照中没有匹配的节点；目标可能不在屏幕内、被过滤或需要滚动", details)
            }
            if (candidates.none { it.optBoolean("visible") }) {
                details.put("reason", "not_visible")
                throw PhoneToolError("node_not_found", "匹配节点当前不可见；请滚动、切换窗口或重新观察", details)
            }
            if (candidates.none { it.optBoolean("bounds_valid") }) {
                details.put("reason", "invalid_bounds")
                throw PhoneToolError("node_not_found", "匹配节点的屏幕边界无效；请重新观察，不要按旧坐标点击", details)
            }
            details.put("reason", "occluded")
            throw PhoneToolError("target_obscured", "匹配节点被其他窗口遮挡；请先处理遮挡窗口或改用可见目标", details)
        }
        if (matches.size != 1) fail("ambiguous_target", "匹配到 ${matches.size} 个目标，请补充 resource_id、文字或窗口条件")
        return matches.single()
    }
}
