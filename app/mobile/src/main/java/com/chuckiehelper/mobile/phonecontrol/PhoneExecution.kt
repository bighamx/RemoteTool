package com.chuckiehelper.mobile.phonecontrol

import org.json.JSONArray
import org.json.JSONObject

internal object PhoneExecution {
    val actions = setOf("phone_tap", "phone_input_text", "phone_key", "phone_swipe", "phone_gesture", "phone_ui_action", "phone_launch_app")
    val batchTools = actions + setOf("phone_observe", "phone_find_nodes", "phone_wait", "phone_get_capabilities")
    fun validateCoordinates(name: String, args: JSONObject, width: Int, height: Int) {
        fun point(x: Double, y: Double) { require(x.isFinite() && y.isFinite() && x >= 0 && y >= 0 && x < width && y < height) { "Coordinates must lie within the physical display" } }
        when (name) {
            "phone_tap" -> if (args.has("x") || args.has("y")) point(args.getDouble("x"), args.getDouble("y"))
            "phone_swipe" -> { point(args.getDouble("x1"), args.getDouble("y1")); point(args.getDouble("x2"), args.getDouble("y2")) }
            "phone_gesture" -> {
                val strokes = args.getJSONArray("strokes"); require(strokes.length() in 1..10)
                for (i in 0 until strokes.length()) {
                    val points = strokes.getJSONObject(i).getJSONArray("points"); require(points.length() in 1..100)
                    for (j in 0 until points.length()) { val p = points.getJSONArray(j); require(p.length() == 2); point(p.getDouble(0), p.getDouble(1)) }
                }
            }
        }
    }
    fun validateOptions(args: JSONObject) {
        args.optJSONObject("selector")?.let(PhoneSelectors::validate)
        args.optJSONObject("guard")?.let { guard ->
            require(guard.length() > 0) { "guard must contain a condition" }
            require(guard.keys().asSequence().all { it in setOf("package_name", "selector") }) { "Unknown guard field" }
            if (guard.has("package_name")) require(guard.get("package_name") is String && guard.getString("package_name").isNotBlank())
            if (guard.has("selector")) PhoneSelectors.validate(guard.getJSONObject("selector"))
        }
        args.optJSONObject("wait_for")?.let(::validateWait)
        if (args.has("return_state")) require(args.getString("return_state") in setOf("none", "compact", "text", "raw")) { "Invalid return_state" }
    }
    fun validateWait(args: JSONObject) {
        require(args.keys().asSequence().all { it in setOf("text", "text_regex", "package_name", "selector", "absent", "stable_ms", "timeout_ms", "explanation") }) { "Unknown wait condition" }
        for (key in listOf("text", "text_regex", "package_name")) if (args.has(key)) require(args.get(key) is String)
        if (args.has("text_regex")) PhoneSelectors.validate(JSONObject().put("text_regex", args.getString("text_regex")))
        args.optJSONObject("selector")?.let(PhoneSelectors::validate)
        if (args.has("selector")) require(args.get("selector") is JSONObject)
        if (args.has("absent")) require(args.get("absent") is Boolean && args.has("selector")) { "absent requires selector" }
        for ((key, range) in listOf("stable_ms" to 0..2000, "timeout_ms" to 1..10000)) if (args.has(key))
            require(args.get(key) is Number && args.getDouble(key) == args.getInt(key).toDouble() && args.getInt(key) in range) { "Invalid $key" }
        require(args.has("text") || args.has("text_regex") || args.has("selector") || args.has("package_name") || args.optInt("stable_ms") > 0) { "需要等待条件或 stable_ms" }
    }
    fun validateBatch(args: JSONObject): List<Pair<String, JSONObject>> {
        val steps = args.getJSONArray("steps")
        require(steps.length() in 1..20) { "steps must contain 1–20 actions" }
        if (args.has("max_ms")) require(args.getInt("max_ms") in 100..30000) { "max_ms must be 100–30000" }
        validateOptions(args)
        return (0 until steps.length()).map { i ->
            val step = steps.getJSONObject(i)
            require(step.keys().asSequence().all { it in setOf("tool", "arguments") }) { "Unknown step field" }
            val name = step.getString("tool")
            require(name in batchTools) { "Tool $name cannot be used in a batch" }
            val arguments = step.optJSONObject("arguments") ?: if (!step.has("arguments")) JSONObject() else error("Step arguments must be an object")
            PhoneToolCatalog.validate(name, arguments)
            validateOptions(arguments)
            if (name == "phone_wait") validateWait(arguments)
            name to arguments
        }
    }
    fun matched(data: JSONObject, args: JSONObject): Boolean {
        if (args.has("package_name") && data.optString("foreground_package", data.optString("package")) != args.getString("package_name")) return false
        val rows = PhoneSelectors.rows(data)
        if (args.has("text") && rows.none { it.optString("text").contains(args.getString("text")) || it.optString("description").contains(args.getString("text")) }) return false
        if (args.has("text_regex")) {
            val expression = Regex(args.getString("text_regex"), RegexOption.IGNORE_CASE)
            if (rows.none { expression.containsMatchIn(it.optString("text") + "\n" + it.optString("description")) }) return false
        }
        args.optJSONObject("selector")?.let { selector ->
            val found = rows.any { PhoneSelectors.matches(it, selector) }
            if (found == args.optBoolean("absent")) return false
        }
        return true
    }
}
