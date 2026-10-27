package com.chuckiehelper.mobile.phonecontrol

import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

/** UiAutomation observations are read-only snapshots, never Accessibility node handles. */
internal object PhoneUiDump {
    fun parse(xml: String, limit: Int, options: JSONObject, screen: JSONObject, privilege: String): JSONObject {
        val parser = android.util.Xml.newPullParser()
        parser.setInput(java.io.StringReader(xml))
        val viewport = Rect(0, 0, screen.getInt("screen_width"), screen.getInt("screen_height"))
        val nodes = JSONArray()
        val filter = options.optJSONObject("filter")?.also(PhoneSelectors::validate)
        val contains = options.optJSONObject("_find")
        val mode = options.optString("mode", "compact")
        val target = options.optString("package_name")
        var pkg = ""; var visited = 0; var truncated = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "node") {
                if (nodes.length() >= limit || visited >= 2400) { truncated = true; break }
                visited++
                fun attr(key: String) = parser.getAttributeValue(null, key).orEmpty()
                if (pkg.isEmpty()) pkg = attr("package")
                val bounds = Regex("-?\\d+").findAll(attr("bounds")).map { it.value.toInt() }.toList()
                val raw = if (bounds.size == 4) Rect(bounds[0], bounds[1], bounds[2], bounds[3]) else Rect()
                val visible = PhoneScreen.visible(raw, viewport)
                val password = attr("password") == "true"
                val row = JSONObject().put("node_id", "p$visited")
                    .put("text", if (password) "" else attr("text")).put("description", if (password) "" else attr("content-desc"))
                    .put("resource_id", attr("resource-id")).put("class", attr("class")).put("package_name", attr("package"))
                    .put("bounds", PhoneScreen.json(visible ?: Rect())).put("raw_bounds", PhoneScreen.json(raw))
                    .put("bounds_valid", visible != null).put("visible", visible != null).put("occluded", false)
                    .put("clickable", attr("clickable") == "true").put("editable", attr("class").endsWith("EditText"))
                    .put("enabled", attr("enabled") == "true").put("focused", attr("focused") == "true")
                    .put("password", password).put("depth", parser.depth).put("actions", JSONArray())
                if (visible != null) row.put("tap_point", JSONObject().put("x", visible.centerX()).put("y", visible.centerY()))
                val useful = row.optString("text").isNotBlank() || row.optString("description").isNotBlank() || row.optBoolean("clickable") || row.optBoolean("editable")
                if ((target.isEmpty() || row.optString("package_name") == target) &&
                    (mode == "raw" || visible != null && useful) &&
                    (filter == null || PhoneSelectors.matches(row, filter)) &&
                    (contains == null || contains.keys().asSequence().all { row.optString(it).contains(contains.getString(it), true) }))
                    nodes.put(row)
            }
            parser.next()
        }
        if (target.isNotEmpty() && target != pkg) fail("window_not_found", "UiAutomation 只读取当前窗口，目标应用不在当前根窗口")
        val result = JSONObject(screen.toString()).put("snapshot_id", java.util.UUID.randomUUID().toString())
            .put("package", pkg).put("foreground_package", pkg).put("foreground_source", "uiautomator_root")
            .put("backend", "uiautomator").put("privilege_backend", privilege)
            .put("include_unimportant", options.optBoolean("include_unimportant", true))
            .put("nodes", nodes).put("mode", mode).put("node_actions", false)
            .put("truncated", truncated).put("visited_nodes", visited)
            .put("delta", false).put("reset", options.has("changed_since"))
            .put("occlusion_checked", false)
            .put("instruction", "Read-only UiAutomation nodes. Reobserve using accessibility for node actions; coordinate taps require fresh visual verification.")
        if (mode == "text") result.put("text", (0 until nodes.length()).map { nodes.getJSONObject(it) }
            .flatMap { listOf(it.optString("text"), it.optString("description")) }.filter { it.isNotBlank() }.distinct().joinToString("\n"))
        options.optJSONArray("fields")?.let { requested ->
            val allowed = setOf("node_id", "depth", "text", "description", "resource_id", "class", "package_name", "bounds", "raw_bounds",
                "bounds_valid", "visible", "occluded", "clickable", "editable", "enabled", "focused", "password", "actions", "tap_point", "window_id")
            val keep = (0 until requested.length()).map { requested.getString(it).also { field -> require(field in allowed) } }.toSet() +
                setOf("node_id", "bounds", "bounds_valid", "visible", "tap_point")
            result.put("nodes", JSONArray((0 until nodes.length()).map { i ->
                val row = nodes.getJSONObject(i)
                JSONObject().apply { row.keys().forEach { key -> if (key in keep) put(key, row.get(key)) } }
            }))
        }
        return result
    }
}
