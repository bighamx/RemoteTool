package com.chuckiehelper.mobile.phonecontrol

import org.json.JSONObject

internal object PhoneToolPolicy {
    val uiTools = setOf("phone_observe", "phone_find_nodes", "phone_screenshot", "phone_tap", "phone_swipe", "phone_input_text",
        "phone_key", "phone_launch_app", "phone_wait", "phone_ui_action", "phone_gesture")
    fun requiresUi(name: String, args: JSONObject): Boolean = name in uiTools ||
        name == "phone_shell" && args.optBoolean("ui_operation") ||
        name == "phone_intent" && args.optString("mode", "activity") == "activity" ||
        name == "phone_android" && args.optString("action") == "wake" ||
        name == "phone_notifications" && args.optString("action") in setOf("open", "action")
    fun timeoutMs(name: String, args: JSONObject) = if (name == "phone_manage_app" && args.optString("action") == "install") 120_000L else 35_000L
}
