package com.chuckiehelper.mobile.nativeui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CloudflareCloudConfigurationTest {
    private fun row(id: String = "cf1") = obj("id" to id, "name" to "Account", "accountId" to "a".repeat(32),
        "apiToken" to "test-token", "accessKeyId" to "access", "secretAccessKey" to "secret")

    @Test fun cloudMergePreservesDevicesUnknownSettingsAndDoesNotMutateOriginal() {
        val original = JSONObject("""{"schemaVersion":1,"devices":[{"id":"computer"}],"settings":{"appearance":"dark","future":{"x":1}},"futureRoot":true}""")
        val merged = CloudflareCloudConfiguration.merge(original, JSONArray().put(row()))
        assertTrue(merged.getBoolean("futureRoot"))
        assertEquals("computer", merged.getJSONArray("devices").getJSONObject(0).getString("id"))
        assertEquals(1, merged.getJSONObject("settings").getJSONObject("future").getInt("x"))
        assertEquals("test-token", merged.getJSONObject("settings").getJSONObject("cloudflare").getJSONArray("accounts").getJSONObject(0).getString("apiToken"))
        assertFalse(original.getJSONObject("settings").has("cloudflare"))
    }

    @Test fun emptyCloudSectionIsRetainedToPreventReimportingDeletedAccounts() {
        val merged = CloudflareCloudConfiguration.merge(JSONObject("""{"settings":{}}"""), JSONArray())
        assertEquals(0, merged.getJSONObject("settings").getJSONObject("cloudflare").getJSONArray("accounts").length())
    }

    @Test fun validatesCredentialsAndRejectsDuplicateIdentities() {
        assertEquals(1, CloudflareCloudConfiguration.validate(JSONArray().put(row())).length())
        for (rows in listOf(JSONArray().put(row()).put(row()), JSONArray().put(row().put("apiToken", "")),
            JSONArray().put(row().put("accountId", "bad")), JSONArray().put(row().put("secretAccessKey", "")))) {
            try { CloudflareCloudConfiguration.validate(rows); fail("invalid CF config accepted") } catch (_: IllegalArgumentException) { }
        }
    }
}
