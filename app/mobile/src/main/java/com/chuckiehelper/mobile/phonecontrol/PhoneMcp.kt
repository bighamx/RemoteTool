package com.chuckiehelper.mobile.phonecontrol

import org.json.JSONArray
import org.json.JSONObject

/** Stateless Streamable HTTP, explicitly negotiated 2025-11-25/06-18/03-26 profile. */
class PhoneMcp(private val list: (Boolean) -> JSONArray, private val call: (String, JSONObject, String, Boolean) -> JSONObject) {
    fun handle(message: JSONObject, allowAdvanced: Boolean = true): JSONObject? {
        val id = message.opt("id") ?: JSONObject.NULL
        if (message.optString("jsonrpc") != "2.0" || !message.has("method")) return error(id, -32600, "Invalid Request")
        val method = message.getString("method")
        if (!message.has("id")) return null
        val p = message.optJSONObject("params") ?: JSONObject()
        return try {
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", p.optString("protocolVersion").takeIf { it in versions } ?: versions.first())
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "remotetool-phone").put("version", "1.1.0"))
                    .put("instructions", "Android phone tools. Add optional explanation to any tool call to briefly explain the action and purpose in the user's language (max 240 characters); it appears with the operation on the phone, so no separate notification is needed. Do not include credentials or hidden reasoning. Use phone_begin_ui before a UI batch and phone_end_ui when finished. Use phone_notify_user(kind=help) to request user assistance, then phone_get_user_reply to check completion. Call phone_disconnect as your final phone tool when done to immediately dismiss your activity indicator and pending notices. Observe before acting and verify afterward. Never automatically retry a delivery_unknown result.")
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", list(allowAdvanced))
                "tools/call" -> {
                    if (p.has("arguments") && p.opt("arguments") !is JSONObject) return error(id, -32602, "arguments must be an object")
                    val name = p.getString("name"); val arguments = p.optJSONObject("arguments") ?: JSONObject()
                    // JSON-RPC IDs are connection-local, not global idempotency keys.
                    val key = p.optJSONObject("_meta")?.optString("remotetool/request_id")?.takeIf { it.isNotBlank() }
                        ?: "mcp-" + java.util.UUID.randomUUID()
                    call(name, arguments, key, allowAdvanced)
                }
                else -> return error(id, -32601, "Method not found")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (e: Exception) { error(id, -32602, e.message ?: "Invalid params") }
    }
    companion object {
        val versions = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        fun error(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
    }
}
