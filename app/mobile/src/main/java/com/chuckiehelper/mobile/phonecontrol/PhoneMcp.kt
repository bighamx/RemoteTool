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
                    .put("serverInfo", JSONObject().put("name", "remotetool-phone").put("version", "1.1.0"))
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
            Android phone control: keep ONE logical MCP session for the entire ongoing task, including observation, UI batches, files, user assistance and verification. Reuse the existing configured MCP client; do not create an ad-hoc client or initialize for each tool call, shell command, UI batch or screenshot. A new HTTP/TCP connection is fine; a new initialize creates a DIFFERENT phone identity even with the same clientInfo.name.
            For custom HTTP clients, initialize once, capture the Mcp-Session-Id RESPONSE HEADER and negotiated protocolVersion, then send that same session header and MCP-Protocol-Version on every subsequent request, including tools/list, tools/call, ping and DELETE. Sequential short-lived script processes must share a protected session cache scoped by endpoint, credential identity and logical agent; independent agents must not share a session. Serialize first initialization so concurrent processes do not create duplicate sessions. If unable to preserve the session, use a persistent MCP client or the long-lived stdio adapter instead. Never switch to headerless legacy calls after initialize. RemoteTool-injected phone_* tools already manage identity: call those tools directly, without building another MCP client.
            First check phone_get_capabilities. Before reading or changing the screen, call phone_begin_ui on THIS SAME session, then observe, act and verify in order. UI ownership, node snapshots and user notices belong to the session. Use fresh snapshot_id/node_id from this session (30-second validity). Set ui_operation=true for shell commands that inspect or change UI. phone_end_ui releases only UI ownership; keep the connection for subsequent batches and non-UI work. No UI renewal is needed; ownership releases after 10 minutes without UI activity. Other sessions may perform non-UI work concurrently. On ui_busy/device_busy, wait or explain the conflict; never initialize a new identity to bypass it or release another agent's UI ownership.
            Only HTTP 404 session_not_found permits discarding an expired session and initializing once again; then check capabilities, reacquire UI and observe before continuing. Do not blindly replay the failed operation. On connection_closed_by_user, stop and report that the user disconnected this client; do not automatically reconnect. Resume with a new session only after the user authorizes continuation. On delivery_unknown, timeout or lost response, execution may already have happened: do not automatically retry writes or generate a new request_id to bypass deduplication. Verify state/receipt first; request IDs identify logical operations, not connections or JSON-RPC message IDs.
            Include optional explanation only when exposed by the tool schema: a brief action and purpose in the user's language, at most 240 characters, without credentials, sensitive input or hidden reasoning. Request assistance with phone_notify_user(kind=help), retain notice_id and check phone_get_user_reply on the same session; pending/expired/dismissed is not completion. Release UI while waiting for manual interaction and reacquire before resuming.
            When the entire phone task is finished, blocked or abandoned, release your UI in cleanup and call phone_disconnect as the final phone tool, even on failure. Do not disconnect between individual calls or batches. An HTTP client may then DELETE the same MCP URL with its own Mcp-Session-Id to dispose the transport session; closing a process/socket alone does not disconnect the phone identity. After cleanup, do not call phone tools merely to verify disconnection. Do not revoke the device binding or disconnect other agents. Never claim success without verifying the actual result.
        """.trimIndent()
        val versions = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        fun error(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
    }
}
