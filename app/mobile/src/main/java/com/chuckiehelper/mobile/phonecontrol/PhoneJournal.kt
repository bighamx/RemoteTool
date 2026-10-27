package com.chuckiehelper.mobile.phonecontrol

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Durable duplicate suppression. Diagnostic states never authorize replay. */
class PhoneJournal(context: Context) : SQLiteOpenHelper(context, "phone-tools.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, digest TEXT NOT NULL, state TEXT NOT NULL, result TEXT, created INTEGER NOT NULL, owner TEXT NOT NULL DEFAULT '', started_at INTEGER, finished_at INTEGER)") }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) db.execSQL("ALTER TABLE calls ADD COLUMN owner TEXT NOT NULL DEFAULT ''")
        if (old < 3) {
            db.execSQL("ALTER TABLE calls ADD COLUMN started_at INTEGER")
            db.execSQL("ALTER TABLE calls ADD COLUMN finished_at INTEGER")
        }
    }
    init { writableDatabase.execSQL("UPDATE calls SET state='interrupted' WHERE state IN ('claimed','executing')") }
    private fun stateReason(state: String): String = when (state) {
        "completed_pruned" -> "receipt_pruned"
        "interrupted" -> "journal_reopened"
        "unknown" -> "legacy_unknown"
        else -> state
    }
    @Synchronized fun existing(id: String, digest: String): JSONObject? {
        readableDatabase.rawQuery("SELECT digest,state,result FROM calls WHERE id=?", arrayOf(id)).use { row ->
            if (!row.moveToFirst()) return null
            if (row.getString(0) != digest) fail("request_conflict", "请求标识已用于不同参数")
            if (row.getString(1) == "completed") return JSONObject(row.getString(2))
            val state = row.getString(1)
            val reason = stateReason(state)
            val message = when (state) {
                "completed_pruned" -> "该请求已有最终回执，但正文已裁剪；不能据此重新执行原操作"
                "interrupted" -> "该请求是恢复时发现的未完成记录，副作用状态未知；不会重复执行"
                "claimed" -> "该请求已接纳，可能仍在前置检查，尚无最终回执；不会重复执行"
                "executing" -> "该请求已记录执行接纳，尚无最终回执；不会重复执行"
                else -> "该请求的历史状态无法确定，请核对；不会重复执行"
            }
            throw PhoneToolError("delivery_unknown", message, JSONObject().put("state", state).put("state_reason", reason).put("receipt_available", false))
        }
    }
    @Synchronized fun begin(id: String, digest: String, owner: String = "") {
        writableDatabase.insertOrThrow("calls", null, ContentValues().apply { put("id", id); put("digest", digest); put("state", "claimed"); put("created", System.currentTimeMillis()); put("owner", owner) })
    }
    @Synchronized fun markStarted(id: String) {
        val changed = try { writableDatabase.update("calls", ContentValues().apply {
            put("state", "executing"); put("started_at", System.currentTimeMillis())
        }, "id=? AND state='claimed'", arrayOf(id)) }
        catch (_: android.database.sqlite.SQLiteException) { fail("delivery_unknown", "执行接纳标记写入失败，状态待核对；未继续执行工具，不自动重发") }
        if (changed != 1) fail("delivery_unknown", "请求的执行记录已变化；不会重新执行，请核对原回执")
    }
    @Synchronized fun claim(id: String, digest: String, owner: String = ""): JSONObject? {
        existing(id, digest)?.let { return it }
        begin(id, digest, owner); return null
    }
    @Synchronized fun finish(id: String, result: JSONObject) {
        writableDatabase.update("calls", ContentValues().apply { put("state", "completed"); put("result", result.toString()); put("finished_at", System.currentTimeMillis()) }, "id=?", arrayOf(id))
        // Keep a bounded result cache; retain digests as tombstones to prevent replay.
        writableDatabase.execSQL("UPDATE calls SET state='completed_pruned',result=NULL WHERE state='completed' AND result IS NOT NULL AND id<>? AND id NOT IN (SELECT id FROM calls WHERE state='completed' AND result IS NOT NULL AND id<>? ORDER BY COALESCE(finished_at,created) DESC,rowid DESC LIMIT 39)", arrayOf(id, id))
    }
    @Synchronized fun status(id: String, owner: String? = null): JSONObject = readableDatabase.rawQuery("SELECT state,result,created,started_at,finished_at FROM calls WHERE id=?" + if (owner != null) " AND owner=?" else "", if (owner != null) arrayOf(id, owner) else arrayOf(id)).use {
        if (!it.moveToFirst()) fail("not_found", "请求记录不存在")
        JSONObject().put("request_id", id).put("state", it.getString(0)).put("state_reason", stateReason(it.getString(0))).put("receipt_available", !it.isNull(1)).apply {
            for ((index, name) in listOf(2 to "created", 3 to "started_at", 4 to "finished_at")) put(name, if (it.isNull(index)) JSONObject.NULL else it.getLong(index))
            if (!it.isNull(1)) put("result", JSONObject(it.getString(1)))
        }
    }
}
