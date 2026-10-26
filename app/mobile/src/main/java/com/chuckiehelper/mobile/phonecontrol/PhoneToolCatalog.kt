package com.chuckiehelper.mobile.phonecontrol

import org.json.JSONArray
import org.json.JSONObject

object PhoneToolCatalog {
    const val VERSION = "2"
    val responsive = setOf("phone_begin_ui", "phone_end_ui", "phone_disconnect", "phone_notify_user", "phone_get_user_reply", "phone_get_capabilities", "phone_list_devices")
    val advanced = setOf("phone_shell", "phone_manage_app", "phone_system_setting", "phone_android", "phone_intent", "phone_content", "phone_files", "phone_notifications")
    private fun field(type: String, description: String = "") = JSONObject().put("type", type).put("description", description).apply { if (type == "array") put("items", JSONObject().put("type", "string")) }
    private fun tool(name: String, description: String, fields: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList(), read: Boolean = false): JSONObject {
        val properties = JSONObject(); fields.forEach { (key, value) -> properties.put(key, value) }
        properties.put("explanation", field("string", "Optional brief user-facing explanation of what this call is doing and why, in the user's language (max 240 characters). Shown with the operation on the phone; do not include credentials, sensitive input or hidden reasoning. No separate notification call is needed.").put("maxLength", 240))
        return JSONObject().put("name", name).put("description", description)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required)).put("additionalProperties", false))
            .put("annotations", JSONObject().put("readOnlyHint", read).put("destructiveHint", name in advanced).put("openWorldHint", true))
    }
    private fun buildTools(): List<JSONObject> {
        val node = mapOf("node_id" to field("string"), "snapshot_id" to field("string"))
        val all = listOf(
            tool("phone_disconnect", "Finish this agent's entire phone task: remove its activity indicator, UI ownership and pending notices. Call once as the final phone tool, including failure cleanup; never between individual calls or UI batches. Does not revoke credentials or affect other agents. For another batch in the same task use phone_end_ui instead. HTTP clients may then DELETE their own MCP session; do not make another tool call just to verify cleanup."),
            tool("phone_begin_ui", "Acquire exclusive UI ownership on your EXISTING connection before reading or operating the screen. Reuse this same session for all UI calls; do not initialize a new session for each batch. On ui_busy wait or report the conflict, never create another identity to bypass it. Other connections may continue non-UI work. Ends on phone_end_ui, disconnect, stop or 10 minutes without UI activity. No renewal needed; duration_seconds is ignored.", mapOf("message" to field("string"), "duration_seconds" to field("integer"))),
            tool("phone_end_ui", "Release only your CURRENT session's UI ownership after a batch or before waiting for manual user interaction. Keep this same connection for subsequent batches, assistance replies and non-UI work; call phone_begin_ui again before resuming UI. Does not disconnect the agent. Optionally show a completion message.", mapOf("message" to field("string"))),
            tool("phone_notify_user", "Show a message on the top overlay and in notifications. kind=help requests user assistance; the user can choose Done or Dismiss. Keep the returned notice_id to query their reply. Never infer that an assistance request has been completed.", mapOf("message" to field("string"), "kind" to field("string").put("enum", JSONArray(listOf("info", "help"))), "timeout_seconds" to field("integer")), listOf("message")),
            tool("phone_get_user_reply", "Read the user's reply on the SAME session that created notice_id: pending/done/dismissed/expired/cancelled/not_found. Keep the connection while awaiting assistance, release UI during manual interaction, and poll at reasonable intervals rather than blocking or repeatedly reconnecting. A timeout is not completion.", mapOf("notice_id" to field("string")), listOf("notice_id"), read = true),
            tool("phone_list_devices", "List connected Android phones.", read = true),
            tool("phone_get_capabilities", "Get Android backend availability and screen information.", read = true),
            tool("phone_observe", "Read current UI nodes and optionally screenshot using the SAME session that acquired phone_begin_ui. Coordinates use original screen pixels; snapshot_id/node_id belong to this session and expire after 30 seconds. Observe again after changes; do not reuse another connection's nodes.", mapOf("screenshot" to field("boolean"), "limit" to field("integer")), read = true),
            tool("phone_find_nodes", "Find UI nodes by text/resource_id/description; returns a fresh snapshot.", mapOf("text" to field("string"), "resource_id" to field("string"), "description" to field("string")), read = true),
            tool("phone_tap", "Tap a node with snapshot_id, or original screen x/y. Observe again to verify outcome.", node + mapOf("x" to field("number"), "y" to field("number"), "long_press" to field("boolean"))),
            tool("phone_swipe", "Swipe in original screen pixels.", mapOf("x1" to field("number"), "y1" to field("number"), "x2" to field("number"), "y2" to field("number"), "duration_ms" to field("integer")), listOf("x1", "y1", "x2", "y2")),
            tool("phone_input_text", "Set Unicode text on a node or current focused input. append defaults to false.", node + mapOf("text" to field("string"), "append" to field("boolean")), listOf("text")),
            tool("phone_key", "Accessibility globals: BACK/HOME/RECENTS/NOTIFICATIONS/QUICK_SETTINGS/POWER_DIALOG/SPLIT_SCREEN/LOCK_SCREEN/TAKE_SCREENSHOT. Privileged backend also accepts Android KEYCODE names or numeric strings (ENTER, POWER, VOLUME_UP, etc).", mapOf("key" to field("string")), listOf("key")),
            tool("phone_launch_app", "Launch installed app by package_name.", mapOf("package_name" to field("string")), listOf("package_name")),
            tool("phone_wait", "Wait up to timeout_ms (max 10000) for text or foreground package, then return fresh observation.", mapOf("text" to field("string"), "package_name" to field("string"), "timeout_ms" to field("integer")), read = true),
            tool("phone_list_apps", "List installed Android apps, optionally including system packages; actual OS visibility rules apply.", mapOf("include_system" to field("boolean")), read = true),
            tool("phone_transfer_file", "Upload/download/list files in RemoteTool exchange directory. Max 2 MiB per chunk. Upload offset=0 replaces; subsequent exact offset appends. Download supports offset/length and returns total size; supports large APKs via chunks.", mapOf("action" to field("string"), "name" to field("string"), "data_base64" to field("string"), "offset" to field("integer"), "length" to field("integer")), listOf("action")),
            tool("phone_shell", "Run privileged shell with Shizuku or root. Requires advanced switch. Set ui_operation=true for commands reading or changing screen/UI state; these require phone_begin_ui ownership. Never automatically retry an unknown result.", mapOf("command" to field("string"), "backend" to field("string"), "timeout_ms" to field("integer"), "ui_operation" to field("boolean")), listOf("command")),
            tool("phone_manage_app", "Advanced app action: force_stop, clear, enable, disable, uninstall, install (path must be an uploaded exchange filename).", mapOf("action" to field("string"), "package_name" to field("string"), "name" to field("string"), "backend" to field("string")), listOf("action")),
            tool("phone_system_setting", "Advanced settings get/put/delete for system, secure or global namespace.", mapOf("action" to field("string"), "namespace" to field("string"), "key" to field("string"), "value" to field("string"), "backend" to field("string")), listOf("action", "namespace", "key")),
            tool("phone_android", "Native Android action: device, permissions, clipboard_get/set, volume_get/set (parameters stream/value), ringer_set(value), brightness_set(value 0-255), flashlight(enabled), vibrate(duration_ms), location (cached), sensors, network, bluetooth, sms_send(number/text), wake. Requires advanced and relevant Android permission.", mapOf("action" to field("string"), "parameters" to field("object")), listOf("action")),
            tool("phone_intent", "Start Android activity/service or send broadcast. Supports settings panels, dial/call, camera, audio/video capture, alarms, sharing, application components. Background launch and OS permission limits still apply.", mapOf("action" to field("string"), "uri" to field("string"), "mime_type" to field("string"), "package_name" to field("string"), "component" to field("string"), "mode" to field("string"), "extras" to field("object"), "flags" to field("integer")), listOf("action")),
            tool("phone_content", "Query/insert/update/delete Android content providers, e.g. content://contacts/people, content://com.android.contacts/data, content://com.android.calendar/events, content://sms, content://call_log/calls, content://media/external/images/media. Uses granted system permissions. Query limit max 500; mutations require item URI or selection unless allow_all=true.", mapOf("action" to field("string"), "uri" to field("string"), "projection" to field("array"), "selection" to field("string"), "selection_args" to field("array"), "sort" to field("string"), "limit" to field("integer"), "values" to field("object"), "allow_all" to field("boolean")), listOf("action", "uri")),
            tool("phone_files", "Native files list/stat/read/write/mkdir/delete/move/copy. Absolute Android path or granted content URI (read/write). Binary via data_base64 max 2 MiB. More restricted paths can use privileged phone_shell.", mapOf("action" to field("string"), "path" to field("string"), "destination" to field("string"), "data_base64" to field("string"), "append" to field("boolean"), "overwrite" to field("boolean")), listOf("action", "path")),
            tool("phone_notifications", "Read notifications or dismiss/open/action/reply using granted notification access. Replies require a RemoteInput action.", mapOf("action" to field("string"), "key" to field("string"), "index" to field("integer"), "text" to field("string")), listOf("action")),
            tool("phone_screenshot", "Capture screen using accessibility or authorized Shizuku/root. Returns JPEG image. backend: auto/accessibility/shizuku/root. Protected windows may be unavailable.", mapOf("backend" to field("string")), read = true),
            tool("phone_ui_action", "Act on observed node: focus/clear_focus/select/clear_selection/scroll_forward/scroll_backward/expand/collapse/copy/cut/paste/accessibility_focus/dismiss/set_selection(start,end). action also accepts a numeric Android AccessibilityNodeInfo action ID string.", node + mapOf("action" to field("string"), "start" to field("integer"), "end" to field("integer")), listOf("node_id", "snapshot_id", "action")),
            tool("phone_gesture", "Multi-touch/path gesture. strokes: [{points:[[x,y],[x,y]],start_ms:0,duration_ms:400}]. Screen pixel coordinates, max 10 strokes.", mapOf("strokes" to field("array").put("items", JSONObject().put("type", "object"))), listOf("strokes"))
        )
        return all
    }
    private val allTools by lazy { buildTools() }
    private val schemasByName by lazy { allTools.associate { it.getString("name") to it.getJSONObject("inputSchema") } }
    // Never expose cached mutable JSON objects to transport callers.
    fun tools(allowAdvanced: Boolean): JSONArray = JSONArray(allTools
        .filter { allowAdvanced || it.getString("name") !in advanced }.map { JSONObject(it.toString()) })
    fun validate(name: String, args: JSONObject) {
        val schema = schemasByName[name] ?: fail("unknown_tool", "Unknown tool: $name")
        val fields = schema.getJSONObject("properties")
        for (key in args.keys()) {
            val expected = fields.optJSONObject(key)?.getString("type") ?: fail("invalid_arguments", "Unknown argument: $key")
            val value = args.get(key)
            val valid = when (expected) { "string" -> value is String; "boolean" -> value is Boolean; "integer" -> value is Int || value is Long; "number" -> value is Number; "object" -> value is JSONObject; "array" -> value is JSONArray; else -> false }
            if (!valid) fail("invalid_arguments", "$key must be $expected")
            if (key == "explanation" && (value as String).length > 240) fail("invalid_arguments", "explanation must be at most 240 characters")
        }
        schema.getJSONArray("required").let { required -> for (i in 0 until required.length()) if (!args.has(required.getString(i))) fail("invalid_arguments", "Missing ${required.getString(i)}") }
    }
}
