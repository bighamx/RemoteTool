package com.chuckiehelper.mobile.phonecontrol

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** User-visible local history. Separate from the durable no-replay journal. */
class PhoneConnectionLog(context: Context) : SQLiteOpenHelper(context, "phone-connections.db", null, 1) {
    private val active = linkedMapOf<String, String>()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE connections(id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL, source TEXT NOT NULL, details TEXT NOT NULL, started INTEGER NOT NULL, last_seen INTEGER NOT NULL, ended INTEGER, reason TEXT)")
        db.execSQL("CREATE TABLE activity(id TEXT PRIMARY KEY, connection_id TEXT NOT NULL, request_id TEXT NOT NULL, tool TEXT NOT NULL, started INTEGER NOT NULL, ended INTEGER, state TEXT NOT NULL, arguments TEXT NOT NULL, result TEXT)")
        db.execSQL("CREATE INDEX activity_connection ON activity(connection_id, started DESC)")
        db.execSQL("CREATE TABLE blocked(owner TEXT PRIMARY KEY, created INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) = Unit
    init {
        writableDatabase.execSQL("UPDATE connections SET ended=?,reason='process_restarted' WHERE ended IS NULL", arrayOf(System.currentTimeMillis()))
        writableDatabase.execSQL("UPDATE activity SET ended=?,state='delivery_unknown',result=? WHERE ended IS NULL", arrayOf(System.currentTimeMillis(), "{\"code\":\"delivery_unknown\",\"retryable\":false,\"message\":\"进程退出，操作结果未知，请核对后再操作\"}"))
        trim()
    }
    @Synchronized fun blocked(owner: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM blocked WHERE owner=?", arrayOf(owner)).use { it.moveToFirst() }
    @Synchronized fun requireOpen(owner: String) {
        if (blocked(owner)) throw PhoneToolError("connection_closed_by_user", "用户已在手机端断开此连接；未开始的调用已取消。请重新建立会话后再操作，不要自动重发。", JSONObject().put("requires_reinitialize", true).put("execution", "not_started"))
    }
    @Synchronized fun seen(owner: String, name: String, source: String = if (owner.startsWith("web:")) "转发通道" else "直连手机", details: JSONObject = JSONObject()): String {
        requireOpen(owner)
        val now = System.currentTimeMillis()
        val id = active[owner] ?: UUID.randomUUID().toString().also { active[owner] = it
            writableDatabase.insertOrThrow("connections", null, ContentValues().apply {
                put("id", it); put("owner", owner); put("name", name.take(80)); put("source", source); put("details", safeJson(details)); put("started", now); put("last_seen", now)
            })
        }
        writableDatabase.update("connections", ContentValues().apply {
            put("name", name.take(80)); put("last_seen", now)
            if (details.length() > 0) put("details", safeJson(details))
        }, "id=?", arrayOf(id))
        return id
    }
    @Synchronized fun touch(owner: String) { active[owner]?.let { writableDatabase.execSQL("UPDATE connections SET last_seen=? WHERE id=?", arrayOf(System.currentTimeMillis(), it)) } }
    @Synchronized fun disconnect(owner: String, reason: String = "agent_disconnected", byUser: Boolean = false) {
        if (byUser) writableDatabase.insertWithOnConflict("blocked", null, ContentValues().apply { put("owner", owner); put("created", System.currentTimeMillis()) }, SQLiteDatabase.CONFLICT_REPLACE)
        active.remove(owner)?.let {
            val now = System.currentTimeMillis()
            writableDatabase.update("connections", ContentValues().apply { put("ended", now); put("reason", reason) }, "id=?", arrayOf(it))
            if (byUser) writableDatabase.insertOrThrow("activity", null, ContentValues().apply {
                put("id", UUID.randomUUID().toString()); put("connection_id", it); put("request_id", "local-user-action"); put("tool", "用户断开连接")
                put("started", now); put("ended", now); put("state", "connection_closed_by_user"); put("arguments", "{}")
                put("result", "{\"code\":\"connection_closed_by_user\",\"retryable\":false,\"message\":\"用户在手机端主动断开连接；未开始的调用已取消，已开始的操作请核对回执\"}")
            })
        }
    }
    @Synchronized fun disconnectAll(reason: String, prefix: String = "") { active.keys.filter { it.startsWith(prefix) }.toList().forEach { disconnect(it, reason) } }
    @Synchronized fun begin(owner: String, name: String, request: String, tool: String, arguments: JSONObject, queued: Boolean = false): String {
        val connection = seen(owner, name)
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("activity", null, ContentValues().apply {
            put("id", id); put("connection_id", connection); put("request_id", request.take(160)); put("tool", tool.take(100)); put("started", System.currentTimeMillis()); put("state", if (queued) "queued" else "running"); put("arguments", safeJson(arguments))
        })
        return id
    }
    @Synchronized fun markRunning(id: String) { writableDatabase.execSQL("UPDATE activity SET state='running' WHERE id=? AND ended IS NULL", arrayOf(id)) }
    @Synchronized fun finish(id: String, result: JSONObject) {
        val structured = result.optJSONObject("structuredContent") ?: result
        writableDatabase.update("activity", ContentValues().apply {
            put("ended", System.currentTimeMillis()); put("state", if (result.optBoolean("isError")) structured.optString("code", "error") else "completed"); put("result", safeJson(structured))
        }, "id=?", arrayOf(id))
        trim()
    }
    @Synchronized fun connections(history: Boolean, offset: Int = 0): List<JSONObject> {
        // Expire stale displays while preserving the local history.
        active.keys.toList().forEach { owner ->
            val id = active[owner]!!
            readableDatabase.rawQuery("SELECT last_seen FROM connections WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst() && System.currentTimeMillis() - it.getLong(0) > 30 * 60_000L) disconnect(owner, "idle_expired")
            }
        }
        return query("SELECT c.*, (SELECT COUNT(*) FROM activity a WHERE a.connection_id=c.id) call_count FROM connections c WHERE ended IS ${if (history) "NOT " else ""}NULL ORDER BY started DESC LIMIT 100 OFFSET ?", arrayOf(offset.coerceAtLeast(0).toString()))
    }
    @Synchronized fun connection(id: String): JSONObject? = query("SELECT * FROM connections WHERE id=?", arrayOf(id)).firstOrNull()
    @Synchronized fun calls(id: String, offset: Int = 0): List<JSONObject> = query("SELECT id,request_id,tool,started,ended,state FROM activity WHERE connection_id=? ORDER BY started DESC LIMIT 100 OFFSET ?", arrayOf(id, offset.coerceAtLeast(0).toString()))
    @Synchronized fun call(id: String): JSONObject? = query("SELECT * FROM activity WHERE id=?", arrayOf(id)).firstOrNull()
    @Synchronized fun clearHistory() {
        writableDatabase.delete("activity", "ended IS NOT NULL", null)
        writableDatabase.delete("connections", "ended IS NOT NULL", null)
    }
    private fun query(sql: String, args: Array<String>): List<JSONObject> = readableDatabase.rawQuery(sql, args).use { cursor -> buildList {
        while (cursor.moveToNext()) add(JSONObject().apply { cursor.columnNames.forEachIndexed { index, column -> put(column, when (cursor.getType(index)) { Cursor.FIELD_TYPE_NULL -> JSONObject.NULL; Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index); else -> cursor.getString(index) }) } })
    } }
    private fun trim() {
        val before = System.currentTimeMillis() - 30L * 24 * 60 * 60_000
        writableDatabase.execSQL("DELETE FROM activity WHERE ended IS NOT NULL AND (started<? OR id NOT IN (SELECT id FROM activity ORDER BY started DESC LIMIT 20000))", arrayOf(before))
        writableDatabase.execSQL("DELETE FROM connections WHERE ended IS NOT NULL AND (started<? OR id NOT IN (SELECT id FROM connections ORDER BY started DESC LIMIT 20000)) AND id NOT IN (SELECT connection_id FROM activity)", arrayOf(before))
    }
    companion object {
        private val secrets = Regex("(?i)(authorization|cookie|token|password|passwd|secret|api[_-]?key)")
        internal fun safeJson(value: Any?): String {
            var budget = 48_000
            fun clean(node: Any?, depth: Int = 0): Any? {
                if (depth > 12 || budget <= 0) return "[内容已截断]"
                return when (node) {
                    is JSONObject -> JSONObject().apply { node.keys().forEach { key ->
                        budget -= key.length
                        put(key, when { secrets.containsMatchIn(key) -> "[敏感内容已隐藏]"; key.contains("base64", true) || key == "data" && node.optString("type") == "image" -> "[二进制内容未记录]"; else -> clean(node.opt(key), depth + 1) })
                    } }
                    is JSONArray -> JSONArray().apply { for (i in 0 until minOf(node.length(), 300)) { put(clean(node.opt(i), depth + 1)); if (budget <= 0) break }; if (node.length() > length()) put("[列表已截断]") }
                    is String -> node.take(minOf(8_000, budget.coerceAtLeast(0))).also { budget -= it.length }.let { if (it.length < node.length) "$it…[已截断]" else it }
                    else -> node
                }
            }
            return clean(value).toString()
        }
    }
}
