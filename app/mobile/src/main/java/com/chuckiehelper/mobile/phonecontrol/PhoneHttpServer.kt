package com.chuckiehelper.mobile.phonecontrol

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

class PhoneHttpServer(private val engine: PhoneEngine) : NanoHTTPD("0.0.0.0", engine.settings.port) {
    private val sessions = PhoneMcpSessions()
    override fun serve(session: IHTTPSession): Response {
        if (!engine.settings.enabled) return json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "disabled"))
        // Native MCP clients need no Origin. Browser origins are deliberately not accepted.
        if (session.headers.containsKey("origin")) return json(Response.Status.FORBIDDEN, JSONObject().put("error", "origin_not_allowed"))
        val bearer = session.headers["authorization"].orEmpty().removePrefix("Bearer ")
        if (!engine.settings.authenticate(bearer)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "unauthorized"))
        try {
            val path = session.uri
            if (path == "/mcp") {
                val sessionId = session.headers["mcp-session-id"].orEmpty()
                if (session.method == Method.DELETE) {
                    val resume = session.headers["x-remotetool-resume-token"].orEmpty()
                    if (resume.isNotEmpty() && sessionId.isNotEmpty() || resume.isEmpty() && sessionId.isEmpty())
                        return json(Response.Status.BAD_REQUEST, JSONObject().put("code", "session_required").put("message", "Specify exactly one owner credential"))
                    val client = try { if (resume.isNotEmpty()) sessions.removeByResumeToken(resume) else sessions.remove(sessionId) }
                    catch (error: PhoneToolError) {
                        return json(Response.Status.CONFLICT, JSONObject().put("code", error.code).put("execution", "not_started").put("retry_after_ms", 250))
                    } ?: return json(Response.Status.NOT_FOUND, JSONObject().put("code", if (resume.isEmpty()) "session_not_found" else "resume_unknown"))
                    engine.disconnect(client.owner)
                    return newFixedLengthResponse(Response.Status.NO_CONTENT, "application/json", "")
                }
                if (session.method != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "POST required")).apply { addHeader("Allow", "POST") }
                val version = session.headers["mcp-protocol-version"]
                if (version != null && version !in PhoneMcp.versions) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "unsupported_protocol_version"))
                val accept = session.headers["accept"].orEmpty()
                if (!accept.contains("application/json") || !accept.contains("text/event-stream")) return json(Response.Status.NOT_ACCEPTABLE, JSONObject().put("error", "Accept must include application/json and text/event-stream"))
                val message = body(session)
                val initializing = message.optString("method") == "initialize"
                if (!initializing && sessionId.isNotBlank() && engine.connectionLog.blocked("direct:$sessionId")) {
                    val detail = PhoneEngine.errorResult(PhoneToolError("connection_closed_by_user", "用户已在手机端断开此连接；请重新建立会话，不要自动重发操作。",
                        JSONObject().put("requires_reinitialize", true).put("execution", "not_started")))
                    return json(Response.Status.OK, JSONObject().put("jsonrpc", "2.0").put("id", message.opt("id")).apply {
                        if (message.optString("method") == "tools/call") put("result", detail)
                        else put("error", JSONObject().put("code", -32000).put("message", detail.getJSONObject("structuredContent").getString("message")).put("data", detail.getJSONObject("structuredContent")))
                    })
                }
                val client = if (!initializing && sessionId.isNotBlank()) sessions.get(sessionId)
                    ?: return json(Response.Status.NOT_FOUND, JSONObject().put("code", "session_not_found")) else null
                if (client != null && engine.connectionLog.blocked(client.owner) && message.optString("method") != "tools/call")
                    return json(Response.Status.OK, JSONObject().put("jsonrpc", "2.0").put("id", message.opt("id"))
                        .put("error", JSONObject().put("code", -32000).put("message", "用户已在手机端断开此连接，请重新建立会话，不要自动重发操作")
                            .put("data", JSONObject().put("code", "connection_closed_by_user").put("retryable", false).put("requires_reinitialize", true))))
                val mcp = PhoneMcp({ PhoneToolCatalog.tools(it && engine.settings.advanced) }, { name, args, id, advanced ->
                    if (client == null) engine.invoke(name, args, id, advanced)
                    else try { sessions.withClient(client) { engine.invoke(name, args, id, advanced, agentId = client.owner, agentName = client.name) } }
                    catch (error: Exception) { PhoneEngine.errorResult(error) }
                })
                val result = mcp.handle(message)
                client?.let { engine.agentUi.touchConnection(it.owner) }
                client?.let { engine.connectionLog.touch(it.owner) }
                var created: PhoneMcpSessions.Client? = null
                if (initializing && result != null && !result.has("error")) {
                    val params = message.optJSONObject("params")
                    val meta = params?.optJSONObject("_meta")
                    if (params?.has("_meta") == true && meta == null || meta?.has("remotetool/resume_token") == true &&
                        (meta.opt("remotetool/resume_token") !is String || meta.optString("remotetool/resume_token").isBlank()))
                        return json(Response.Status.OK, PhoneMcp.error(message.opt("id") ?: JSONObject.NULL, -32602, "Invalid recovery metadata"))
                    created = try {
                        sessions.create(params?.optJSONObject("clientInfo")?.optString("name") ?: "Agent",
                            meta?.optString("remotetool/resume_token")) { owner -> !engine.connectionLog.blocked(owner) }
                    } catch (error: PhoneToolError) {
                        return json(Response.Status.OK, PhoneMcp.error(message.opt("id") ?: JSONObject.NULL, -32000, error.message ?: "Recovery failed")
                            .apply { getJSONObject("error").put("data", JSONObject().put("code", error.code)) })
                    }
                    result.getJSONObject("result").put("_meta", JSONObject().put("remotetool", JSONObject().put("agent_id", created.logicalId)
                        .put("resume_token", created.resumeToken).put("generation", created.generation).put("self_cleanup", "resume-token").put("idle_timeout_seconds", 300)))
                    engine.connectionSeen(created.owner, created.name, JSONObject().put("client_info", message.optJSONObject("params")?.optJSONObject("clientInfo"))
                        .put("remote_address", session.remoteIpAddress).put("protocol_version", result.optJSONObject("result")?.optString("protocolVersion")))
                }
                return (if (result == null) newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "") else json(Response.Status.OK, result))
                    .apply { created?.let { addHeader("Mcp-Session-Id", it.id) } }
            }
            if (session.method == Method.GET && path == "/api/phone/v1/devices") return json(Response.Status.OK, JSONObject().put("devices", JSONArray().put(engine.settings.description())))
            if (session.method == Method.GET && path == "/api/phone/v1/devices/${engine.settings.id}/capabilities") return json(Response.Status.OK, engine.capabilities())
            if (session.method == Method.GET && path == "/api/phone/v1/tools") return json(Response.Status.OK, JSONObject().put("tools", PhoneToolCatalog.tools(engine.settings.advanced)))
            if (session.method == Method.POST && path == "/api/phone/v1/invocations") {
                val b = body(session)
                val args = b.optJSONObject("arguments") ?: JSONObject()
                return json(Response.Status.OK, engine.invoke(b.getString("tool"), args, b.getString("request_id"), deadline = b.optLong("deadline", System.currentTimeMillis() + PhoneToolPolicy.timeoutMs(b.getString("tool"), args))))
            }
            if (session.method == Method.GET && path.startsWith("/api/phone/v1/invocations/")) return json(Response.Status.OK, engine.status(path.substringAfterLast('/')))
            return json(Response.Status.NOT_FOUND, JSONObject().put("error", "not_found"))
        } catch (e: Exception) { return json(Response.Status.BAD_REQUEST, PhoneEngine.errorResult(e)) }
    }
    private fun body(session: IHTTPSession): JSONObject {
        if (!session.headers["content-type"].orEmpty().startsWith("application/json")) fail("invalid_arguments", "Content-Type must be application/json")
        val length = session.headers["content-length"]?.toLongOrNull() ?: fail("invalid_arguments", "Content-Length required")
        if (length !in 1..3_000_000) fail("invalid_arguments", "Request body too large")
        // JSON over HTTP defaults to UTF-8. NanoHTTPD otherwise chooses US-ASCII
        // when charset is omitted, replacing Chinese text before JSONObject sees it.
        if (!session.headers["content-type"].orEmpty().contains("charset=", ignoreCase = true))
            session.headers["content-type"] = "application/json; charset=utf-8"
        val data = HashMap<String, String>(); session.parseBody(data)
        return JSONObject(data["postData"] ?: "")
    }
    private fun json(status: Response.Status, data: JSONObject): Response {
        val start = System.nanoTime()
        val body = data.toString()
        val serialized = (System.nanoTime() - start) / 1_000_000.0
        return newFixedLengthResponse(status, "application/json; charset=utf-8", body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-RemoteTool-Serialize-Ms", serialized.toString())
            addHeader("X-RemoteTool-Response-Bytes", body.toByteArray(Charsets.UTF_8).size.toString())
        }
    }
}
