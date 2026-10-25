package com.chuckiehelper.mobile.phonecontrol

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject

class PhoneHttpServer(private val engine: PhoneEngine) : NanoHTTPD("0.0.0.0", engine.settings.port) {
    @Volatile private var clientName = "Agent"
    private val mcp = PhoneMcp({ PhoneToolCatalog.tools(it && engine.settings.advanced) }, { name, args, id, advanced -> engine.invoke(name, args, id, advanced, agentName = clientName) })
    override fun serve(session: IHTTPSession): Response {
        if (!engine.settings.enabled) return json(Response.Status.SERVICE_UNAVAILABLE, JSONObject().put("error", "disabled"))
        // Native MCP clients need no Origin. Browser origins are deliberately not accepted.
        if (session.headers.containsKey("origin")) return json(Response.Status.FORBIDDEN, JSONObject().put("error", "origin_not_allowed"))
        val bearer = session.headers["authorization"].orEmpty().removePrefix("Bearer ")
        if (!engine.settings.authenticate(bearer)) return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "unauthorized"))
        try {
            val path = session.uri
            if (path == "/mcp") {
                if (session.method != Method.POST) return json(Response.Status.METHOD_NOT_ALLOWED, JSONObject().put("error", "POST required")).apply { addHeader("Allow", "POST") }
                val version = session.headers["mcp-protocol-version"]
                if (version != null && version !in PhoneMcp.versions) return json(Response.Status.BAD_REQUEST, JSONObject().put("error", "unsupported_protocol_version"))
                val accept = session.headers["accept"].orEmpty()
                if (!accept.contains("application/json") || !accept.contains("text/event-stream")) return json(Response.Status.NOT_ACCEPTABLE, JSONObject().put("error", "Accept must include application/json and text/event-stream"))
                val message = body(session)
                if (message.optString("method") == "initialize") {
                    clientName = message.optJSONObject("params")?.optJSONObject("clientInfo")?.optString("name")?.take(40)?.ifBlank { "Agent" } ?: "Agent"
                    engine.agentUi.seen("direct", clientName)
                }
                val result = mcp.handle(message)
                return if (result == null) newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", "") else json(Response.Status.OK, result)
            }
            if (session.method == Method.GET && path == "/api/phone/v1/devices") return json(Response.Status.OK, JSONObject().put("devices", JSONArray().put(engine.settings.description())))
            if (session.method == Method.GET && path == "/api/phone/v1/devices/${engine.settings.id}/capabilities") return json(Response.Status.OK, engine.capabilities())
            if (session.method == Method.GET && path == "/api/phone/v1/tools") return json(Response.Status.OK, JSONObject().put("tools", PhoneToolCatalog.tools(engine.settings.advanced)))
            if (session.method == Method.POST && path == "/api/phone/v1/invocations") {
                val b = body(session)
                return json(Response.Status.OK, engine.invoke(b.getString("tool"), b.optJSONObject("arguments") ?: JSONObject(), b.getString("request_id"), deadline = b.optLong("deadline", System.currentTimeMillis() + 35_000)))
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
    private fun json(status: Response.Status, data: JSONObject) = newFixedLengthResponse(status, "application/json; charset=utf-8", data.toString()).apply { addHeader("Cache-Control", "no-store") }
}
