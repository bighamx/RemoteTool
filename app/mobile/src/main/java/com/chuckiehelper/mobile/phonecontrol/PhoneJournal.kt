package com.chuckiehelper.mobile.phonecontrol

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

/** Durable duplicate suppression. Interrupted writes remain unknown and are never replayed. */
class PhoneJournal(context: Context) : SQLiteOpenHelper(context, "phone-tools.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, digest TEXT NOT NULL, state TEXT NOT NULL, result TEXT, created INTEGER NOT NULL, owner TEXT NOT NULL DEFAULT '')") }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { if (old < 2) db.execSQL("ALTER TABLE calls ADD COLUMN owner TEXT NOT NULL DEFAULT ''") }
    init { writableDatabase.execSQL("UPDATE calls SET state='unknown' WHERE state='executing'") }
    @Synchronized fun existing(id: String, digest: String): JSONObject? {
        readableDatabase.rawQuery("SELECT digest,state,result FROM calls WHERE id=?", arrayOf(id)).use { row ->
            if (!row.moveToFirst()) return null
            if (row.getString(0) != digest) fail("request_conflict", "请求标识已用于不同参数")
            if (row.getString(1) == "completed") return JSONObject(row.getString(2))
            fail("delivery_unknown", "该请求正在执行或结果未知，请观察核对；不会重复执行")
        }
    }
    @Synchronized fun begin(id: String, digest: String, owner: String = "") {
        writableDatabase.insertOrThrow("calls", null, ContentValues().apply { put("id", id); put("digest", digest); put("state", "executing"); put("created", System.currentTimeMillis()); put("owner", owner) })
    }
    @Synchronized fun claim(id: String, digest: String, owner: String = ""): JSONObject? {
        existing(id, digest)?.let { return it }
        begin(id, digest, owner); return null
    }
    @Synchronized fun finish(id: String, result: JSONObject) {
        writableDatabase.update("calls", ContentValues().apply { put("state", "completed"); put("result", result.toString()) }, "id=?", arrayOf(id))
        // Keep a bounded result cache; retain digests as tombstones to prevent replay.
        writableDatabase.execSQL("UPDATE calls SET state='unknown',result=NULL WHERE result IS NOT NULL AND id NOT IN (SELECT id FROM calls ORDER BY created DESC LIMIT 40)")
    }
    @Synchronized fun status(id: String, owner: String? = null): JSONObject = readableDatabase.rawQuery("SELECT state,result FROM calls WHERE id=?" + if (owner != null) " AND owner=?" else "", if (owner != null) arrayOf(id, owner) else arrayOf(id)).use {
        if (!it.moveToFirst()) fail("not_found", "请求记录不存在")
        JSONObject().put("request_id", id).put("state", it.getString(0)).apply { if (!it.isNull(1)) put("result", JSONObject(it.getString(1))) }
    }
}
