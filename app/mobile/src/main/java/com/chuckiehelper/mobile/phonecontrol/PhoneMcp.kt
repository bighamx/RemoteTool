package com.chuckiehelper.mobile.phonecontrol

import org.json.JSONArray
import org.json.JSONObject

/** Session-aware Streamable HTTP, explicitly negotiated 2025-11-25/06-18/03-26 profile. */
class PhoneMcp(private val list: (Boolean) -> JSONArray, private val call: (String, JSONObject, String, Boolean) -> JSONObject) {
    fun handle(message: JSONObject, allowAdvanced: Boolean = true): JSONObject? {
        val id = message.opt("id") ?: JSONObject.NULL
        if (message.opt("jsonrpc") != "2.0" || message.opt("method") !is String) return error(id, -32600, "Invalid Request")
        val method = message.getString("method")
        if (!message.has("id")) return null
        if (message.has("params") && message.opt("params") !is JSONObject) return error(id, -32602, "params must be an object")
        val p = message.optJSONObject("params") ?: JSONObject()
        if (method == "initialize" && (p.has("protocolVersion") && p.opt("protocolVersion") !is String ||
                p.has("clientInfo") && p.opt("clientInfo") !is JSONObject ||
                p.optJSONObject("clientInfo")?.let { it.has("name") && it.opt("name") !is String } == true))
            return error(id, -32602, "Invalid initialize params")
        return try {
            val result = when (method) {
                "initialize" -> JSONObject().put("protocolVersion", p.optString("protocolVersion").takeIf { it in versions } ?: versions.first())
                    .put("capabilities", JSONObject().put("tools", JSONObject().put("listChanged", false)))
                    .put("serverInfo", JSONObject().put("name", "remotetool-phone").put("version", "1.2.0"))
                    .put("instructions", instructions)
                "ping" -> JSONObject()
                "tools/list" -> JSONObject().put("tools", list(allowAdvanced))
                "tools/call" -> {
                    if (p.opt("name") !is String || p.getString("name").isBlank()) return error(id, -32602, "name must be a non-empty string")
                    if (p.has("arguments") && p.opt("arguments") !is JSONObject) return error(id, -32602, "arguments must be an object")
                    if (p.has("_meta") && p.opt("_meta") !is JSONObject ||
                        p.optJSONObject("_meta")?.let { it.has("remotetool/request_id") && it.opt("remotetool/request_id") !is String } == true)
                        return error(id, -32602, "Invalid request metadata")
                    val name = p.getString("name"); val arguments = p.optJSONObject("arguments") ?: JSONObject()
                    // JSON-RPC IDs are connection-local, not global idempotency keys.
                    val key = p.optJSONObject("_meta")?.optString("remotetool/request_id")?.takeIf { it.isNotBlank() }
                        ?: "mcp-" + java.util.UUID.randomUUID()
                    call(name, arguments, key, allowAdvanced)
                }
                else -> return error(id, -32601, "Method not found")
            }
            JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
        } catch (e: Exception) { error(id, -32603, "Internal error") }
    }
    companion object {
        val instructions = """
            Control the bound Android phone with configured phone_* tools or the persistent MCP adapter. A missing chat binding is a setup error: use the explicitly configured endpoint, not plugin source or another chat's credentials.
            HTTP: initialize once; retain Mcp-Session-Id and negotiated MCP-Protocol-Version on subsequent requests. Reuse across batches and short-lived processes; isolate caches by endpoint, credentials and logical agent. The shipped CLI accepts --agent <stable-chat-id>. Never share identities between independent agents.
            When initialize returns _meta.remotetool.resume_token, keep it private. After explicit HTTP 404 session_not_found, initialize with params._meta["remotetool/resume_token"] to recover the same owner; the old transport is fenced. device_busy means an old call is still running. On connection_closed_by_user or failed recovery stop, never silently create another identity or bypass ownership.
            Check phone_get_capabilities, then phone_begin_ui before UI reads/actions. Prefer compact observe or find_nodes. If exposed, use selectors, guard, wait_for, return_state and phone_execute for bounded serial actions with fresh targets and one final observation. Prefer condition waits to fixed sleeps and accessibility to shell dumps. Invalid/ambiguous/obscured targets must be resolved, not guessed. Snapshot/node IDs are owner-scoped and expire in 30 seconds. UI shell commands require ui_operation=true.
            accepted is not business success; verify the postcondition. matched=false is a timeout. On delivery_unknown/lost responses never replay writes with a new request_id; query phone_get_operation or observe first. A request_id identifies one logical operation, not a transport or JSON-RPC message. Partial batches return per-step receipts and must not be blindly replayed.
            Use optional explanation for a short user-facing purpose, without secrets or hidden reasoning. For help use phone_notify_user(kind=help), preserve notice_id, release UI and check phone_get_user_reply; pending/dismissed/expired is not completion. Reacquire UI before continuing. phone_end_ui releases only your UI batch; ui_busy/device_busy never permits taking another owner's lock.
            After all work and verification, including failure cleanup, release UI and call phone_disconnect last. HTTP clients may then DELETE their own MCP session. Do not call more phone tools just to verify disconnection, revoke the device binding, or disconnect other agents.
        """.trimIndent()
        val versions = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        fun error(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
    }
}
