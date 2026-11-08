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
            You are operating a real Android phone over RemoteTool MCP. The phone is a separate device; phone success is not desktop success. Work serially; never run UI calls in parallel. Declare your real clientInfo.name during initialize, or client_name in phone_begin_ui on the existing owner; names are display metadata, never authorization.
            Start each task with phone_get_capabilities (no UI ownership required), then phone_begin_ui(client_name, message) before any screen read or action. Reuse the same MCP session for all UI batches, files, help and verification. Do not initialize per call. ui_busy/device_busy means wait and inspect; never create another identity, take another owner's lock or release other agents.
            Read with phone_observe(mode=compact) or phone_find_nodes. Use observe.filter (not selector) to narrow nodes. phone_find_nodes takes top-level text/resource_id/description; it does not take selector. Prefer selectors in phone_tap/phone_input_text: {"selector":{"text":"确定"}} or {"selector":{"resource_id":"pkg:id/button"}}. Do not pass top-level resource_id to an action. phone_tap accepts exactly one target mode: selector, node_id+snapshot_id, or x+y; node_id and snapshot_id must come from the same fresh observe. Snapshot/node IDs are owner-scoped and expire in 30 seconds; after any page change, reobserve instead of guessing coordinates.
            Combine action + condition + readback: use guard to verify the foreground app/target before acting, wait_for for a destination-specific postcondition, and return_state=compact when you need the new UI. matched=false is a timeout, not success; the action is not replayed. Prefer phone_wait/condition waits to fixed sleeps. phone_wait has no return_state argument and always returns a fresh state. Use phone_execute only for a short, already-decided serial flow (1–20 steps, max 30s); stop at the first failed step and inspect per-step receipts. Do not batch steps that require reading and deciding in between.
            Reading backends: auto is the default fast Accessibility path; accessibility requires that service; uiautomator is a slow privileged diagnostic dump whose nodes are read-only. include_unimportant=true retains potentially omitted chat text; mode=compact/text/raw only changes output. Never use shell dumps as routine screen reading. phone_shell that reads/changes UI requires phone_begin_ui and ui_operation=true. Invalid, ambiguous, obscured or stale targets must be reobserved, not guessed or retried blindly.
            HTTP: initialize once; retain Mcp-Session-Id and negotiated MCP-Protocol-Version on every subsequent request. Short-lived processes must reuse a protected cache keyed by endpoint, credentials and logical agent; use the shipped CLI with --agent <stable-chat-id> if available. Never share identities between independent agents. When initialize returns _meta.remotetool.resume_token, keep it private. Only after an explicit HTTP 404 session_not_found, recover serially with params._meta["remotetool/resume_token"]. Other 404s, 401s, timeouts and device_busy do not authorize rebuilding. On connection_closed_by_user or failed recovery, stop and ask the user; never silently create another identity.
            accepted is not business success; verify the postcondition. On delivery_unknown, timeout or lost response, the operation may have run: query phone_get_operation with the same operation_id, or observe the real state, before deciding. Never replay a write with a new request_id. A request_id identifies one logical operation, not a transport or JSON-RPC message. Partial batches return per-step receipts and must not be blindly replayed. Only read-only capability/list/observe/find calls may get one bounded retry after an unknown result.
            Use optional explanation for a short user-facing purpose in the user's language (max 240 chars), without secrets, credentials, sensitive input or hidden reasoning. For user assistance use phone_notify_user(kind=help), preserve notice_id, release UI, and poll phone_get_user_reply on the same session; pending/dismissed/expired/timeout is not completion. Reacquire UI and reobserve before continuing.
            Finish cleanly: phone_end_ui releases only your current UI batch; phone_disconnect is the final phone tool for the entire task, including failure cleanup. Do not call more phone tools merely to verify disconnection, revoke the device binding, or disconnect other agents. HTTP clients may then DELETE their own MCP session.
        """.trimIndent()
        val versions = listOf("2025-11-25", "2025-06-18", "2025-03-26")
        fun error(id: Any, code: Int, message: String) = JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("error", JSONObject().put("code", code).put("message", message))
    }
}
