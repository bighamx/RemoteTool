package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudflareDataTest {
    @Test fun onlySingleReadStatementsSkipTheSqlWriteConfirmation() {
        assertTrue(cloudflareReadOnlySql("/* comment */ SELECT '; DROP TABLE x' AS value; -- trailing comment"))
        assertTrue(cloudflareReadOnlySql("EXPLAIN QUERY PLAN SELECT * FROM t"))
        assertFalse(cloudflareReadOnlySql("SELECT 1; DELETE FROM t"))
        assertFalse(cloudflareReadOnlySql("WITH q AS (SELECT 1) DELETE FROM t"))
        assertFalse(cloudflareReadOnlySql("PRAGMA journal_mode=off"))
        assertFalse(cloudflareReadOnlySql("SELECT 'unterminated"))
        assertFalse(cloudflareReadOnlySql("/* unfinished"))
    }
    @Test fun sdkResultRowsPreserveNullsNestedValuesAndColumnNames() {
        val response = JSONArray("""[{"success":true,"results":[{"name":"a+b/#%","nullValue":null,"nested":{"x":1}}],"meta":{"rows_read":1}},{"success":true,"results":[{"second":2}]}]""")
        val rows = CloudflareModel.queryRows(response)
        assertEquals(2, rows.size)
        assertEquals("a+b/#%", rows.first().getString("name"))
        assertTrue(rows.first().isNull("nullValue"))
        assertEquals(1, rows.first().getJSONObject("nested").getInt("x"))
        assertTrue(CloudflareModel.queryRows(JSONArray("""[{"results":[]}]""")).isEmpty())
        assertTrue(CloudflareModel.queryRows(JSONObject()).isEmpty())
    }
}
