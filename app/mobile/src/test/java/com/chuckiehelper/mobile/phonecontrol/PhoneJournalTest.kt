package com.chuckiehelper.mobile.phonecontrol

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneJournalTest {
    private lateinit var context: Context
    @Before fun setup() { context = ApplicationProvider.getApplicationContext(); context.deleteDatabase("phone-tools.db") }
    private fun receipt() = JSONObject().put("isError", false).put("structuredContent", JSONObject().put("ok", true))
    @Test fun claimAndToolStartHaveDifferentDiagnosticMeaning() {
        PhoneJournal(context).use { j ->
            j.begin("op", "digest", "owner")
            val claim = j.status("op", "owner")
            assertEquals("claimed", claim.getString("state"))
            assertTrue(claim.isNull("started_at")); assertTrue(claim.isNull("finished_at"))
            j.markStarted("op")
            val started = j.status("op", "owner")
            assertEquals("executing", started.getString("state"))
            assertTrue(started.getLong("started_at") >= claim.getLong("created"))
            j.finish("op", receipt())
            val done = j.status("op", "owner")
            assertEquals("completed", done.getString("state")); assertTrue(done.getBoolean("receipt_available"))
            assertTrue(done.getLong("finished_at") >= done.getLong("started_at"))
            assertTrue(j.existing("op", "digest")!!.getJSONObject("structuredContent").getBoolean("ok"))
        }
    }
    @Test fun preflightFailureCanFinishWithoutClaimingToolExecutionStarted() {
        PhoneJournal(context).use { j ->
            j.begin("guard-failure", "digest", "owner")
            j.finish("guard-failure", JSONObject().put("isError", true).put("structuredContent", JSONObject().put("code", "precondition_failed")))
            val row = j.status("guard-failure", "owner")
            assertEquals("completed", row.getString("state"))
            assertTrue(row.isNull("started_at")); assertFalse(row.isNull("finished_at"))
        }
    }
    @Test fun prunedReceiptsKeepOwnerFenceAndNeverReplay() {
        PhoneJournal(context).use { j ->
            for (i in 0..40) { j.begin("op-$i", "digest-$i", "owner"); j.markStarted("op-$i"); j.finish("op-$i", receipt()) }
            val old = j.status("op-0", "owner")
            assertEquals("completed_pruned", old.getString("state"))
            assertFalse(old.getBoolean("receipt_available")); assertFalse(old.isNull("finished_at"))
            assertEquals("receipt_pruned", old.getString("state_reason"))
            val pruned = assertThrows(PhoneToolError::class.java) { j.claim("op-0", "digest-0", "owner") }
            assertEquals("delivery_unknown", pruned.code)
            assertTrue(pruned.message!!.contains("正文已裁剪"))
            assertEquals("receipt_pruned", pruned.details!!.getString("state_reason"))
            assertEquals("request_conflict", assertThrows(PhoneToolError::class.java) { j.claim("op-0", "different", "owner") }.code)
            assertEquals("not_found", assertThrows(PhoneToolError::class.java) { j.status("op-0", "another-owner") }.code)
            assertEquals("completed", j.status("op-40", "owner").getString("state"))
            assertTrue(j.status("op-40", "owner").getBoolean("receipt_available"))
        }
    }
    @Test fun reopeningMarksAdmittedAndStartedCallsInterruptedWithoutInventedFinishTimes() {
        PhoneJournal(context).use { j -> j.begin("admitted", "a", "owner"); j.begin("started", "b", "owner"); j.markStarted("started") }
        PhoneJournal(context).use { j ->
            for (id in listOf("admitted", "started")) {
                val row = j.status(id, "owner")
                assertEquals("interrupted", row.getString("state")); assertTrue(row.isNull("finished_at"))
                assertEquals("delivery_unknown", assertThrows(PhoneToolError::class.java) { j.existing(id, if (id == "admitted") "a" else "b") }.code)
            }
            assertTrue(j.status("admitted").isNull("started_at"))
            assertFalse(j.status("started").isNull("started_at"))
        }
    }
    @Test fun migratingOldUnknownDoesNotInventWhetherItWasPrunedOrInterrupted() {
        val path = context.getDatabasePath("phone-tools.db"); path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE calls(id TEXT PRIMARY KEY,digest TEXT NOT NULL,state TEXT NOT NULL,result TEXT,created INTEGER NOT NULL,owner TEXT NOT NULL DEFAULT '')")
            db.execSQL("INSERT INTO calls VALUES('legacy','digest','unknown',NULL,123,'owner')")
            db.execSQL("INSERT INTO calls VALUES('unfinished','digest2','executing',NULL,124,'owner')")
            db.execSQL("INSERT INTO calls VALUES('cached','digest3','completed',?,125,'owner')", arrayOf(receipt().toString()))
            db.version = 2
        }
        PhoneJournal(context).use { j ->
            val legacy = j.status("legacy", "owner")
            assertEquals("unknown", legacy.getString("state")); assertEquals("legacy_unknown", legacy.getString("state_reason"))
            assertTrue(legacy.isNull("started_at")); assertTrue(legacy.isNull("finished_at"))
            assertEquals("interrupted", j.status("unfinished", "owner").getString("state"))
            assertTrue(j.status("unfinished", "owner").isNull("started_at"))
            assertTrue(j.existing("cached", "digest3")!!.getJSONObject("structuredContent").getBoolean("ok"))
            assertTrue(j.status("cached", "owner").isNull("finished_at"))
            assertEquals(3, j.readableDatabase.version)
        }
    }
    @Test fun lateCompletionRetainsReceiptEvenWhenCreatedBeforeFortyNewerCalls() {
        PhoneJournal(context).use { j ->
            j.begin("slow", "slow", "owner"); j.markStarted("slow")
            for (i in 0..40) { j.begin("fast-$i", "fast-$i", "owner"); j.finish("fast-$i", receipt()) }
            j.finish("slow", receipt())
            assertTrue(j.status("slow", "owner").getBoolean("receipt_available"))
            assertEquals("completed_pruned", j.status("fast-0", "owner").getString("state"))
        }
    }
    @Test fun failedExecutionMarkerWriteReturnsUnknownWithoutStartingTool() {
        PhoneJournal(context).use { j ->
            j.begin("disk-failure", "digest", "owner")
            j.writableDatabase.execSQL("CREATE TRIGGER reject_execution BEFORE UPDATE OF state ON calls WHEN NEW.state='executing' BEGIN SELECT RAISE(ABORT,'injected failure'); END")
            var started = false
            val error = assertThrows(PhoneToolError::class.java) {
                PhoneOperationAdmission.begin(Any(), {}, { j.markStarted("disk-failure") }, { started = true })
            }
            assertEquals("delivery_unknown", error.code); assertFalse(started)
            assertEquals("claimed", j.status("disk-failure", "owner").getString("state"))
        }
    }
    @Test fun statusAndDuplicateErrorsShareStateReasonsAcrossAdmissionAndRecovery() {
        PhoneJournal(context).use { j ->
            j.begin("claimed", "a", "owner"); j.begin("started", "b", "owner"); j.markStarted("started")
            for ((id, digest) in listOf("claimed" to "a", "started" to "b")) {
                val duplicate = assertThrows(PhoneToolError::class.java) { j.existing(id, digest) }
                assertEquals(j.status(id, "owner").getString("state_reason"), duplicate.details!!.getString("state_reason"))
            }
        }
        PhoneJournal(context).use { j ->
            val duplicate = assertThrows(PhoneToolError::class.java) { j.existing("started", "b") }
            assertEquals("journal_reopened", j.status("started", "owner").getString("state_reason"))
            assertEquals(j.status("started", "owner").getString("state_reason"), duplicate.details!!.getString("state_reason"))
        }
    }
    @Test fun engineFinalizesReceiptWhenOwnerIsStoppedDuringExecutionMarkerWrite() {
        context.deleteDatabase("phone-connections.db")
        context.getSharedPreferences("phone-tools", 0).edit().clear().commit()
        val engine = PhoneEngine(context); engine.settings.enabled = true
        val field = PhoneEngine::class.java.getDeclaredField("journal").apply { isAccessible = true }
        val j = field.get(engine) as PhoneJournal
        val activityPath = context.getDatabasePath("phone-connections.db").absolutePath.replace("'", "''")
        try {
            // Test-only SQL trigger simulates user stop DURING markStarted, without
            // adding any production callback or bypassing PhoneEngine's real catch/finalize.
            j.writableDatabase.execSQL("ATTACH DATABASE '$activityPath' AS activity_log")
            j.writableDatabase.execSQL("CREATE TEMP TRIGGER stop_during_marker AFTER UPDATE OF state ON main.calls WHEN NEW.id='cancel-during-persist' AND NEW.state='executing' BEGIN INSERT OR REPLACE INTO blocked(owner,created) VALUES('owner',123); END")
            val response = engine.invoke("phone_get_capabilities", JSONObject(), "cancel-during-persist", agentId = "owner")
            val result = response.getJSONObject("structuredContent")
            assertTrue(response.getBoolean("isError"))
            assertEquals("connection_closed_by_user", result.getString("code"))
            assertEquals("not_started", result.getString("execution"))
            assertFalse(result.has("app"))
            val stored = j.status("cancel-during-persist", "owner")
            assertEquals("completed", stored.getString("state"))
            assertTrue(stored.getBoolean("receipt_available"))
            assertFalse(stored.isNull("started_at")); assertFalse(stored.isNull("finished_at"))
            assertEquals("not_started", stored.getJSONObject("result").getJSONObject("structuredContent").getString("execution"))
            val cached = j.existing("cancel-during-persist", j.readableDatabase.rawQuery("SELECT digest FROM calls WHERE id='cancel-during-persist'", null).use { it.moveToFirst(); it.getString(0) })!!
            assertEquals("not_started", cached.getJSONObject("structuredContent").getString("execution"))
        } finally { j.close(); engine.connectionLog.close() }
    }
}
