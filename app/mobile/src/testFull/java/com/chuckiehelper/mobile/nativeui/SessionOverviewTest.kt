package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SessionOverviewTest {
    @Test fun workspaceIdentityOverridesStaleAccountSnapshot() {
        assertEquals("team", currentCodexIdentity(
            JSONObject().put("current", "personal"),
            JSONObject().put("current", "team")
        ))
        assertEquals("personal", currentCodexIdentity(
            JSONObject().put("current", "personal"), JSONObject()
        ))
    }

    @Test fun explicitWorkspaceLogoutDoesNotReuseOldAccount() {
        assertNull(currentCodexIdentity(JSONObject().put("current", "old-account"),
            JSONObject().put("current", JSONObject.NULL)))
    }

    @Test fun completeOverviewKeepsAllPartsIncludingExplicitNoRun() {
        val overview = SessionOverview.read(JSONObject("""{
            "detail":{"session":{"id":"session-1"}},
            "history":{"data":[],"has_more":false},
            "context":{"available":true},
            "active_run":{"run_id":null},
            "activity":{"available":true,"running":false},
            "files":{"data":[]}
        }"""))
        assertNotNull(overview.detail)
        assertNotNull(overview.history)
        assertNotNull(overview.context)
        assertTrue(overview.activeRun!!.has("run_id"))
        assertNotNull(overview.activity)
        assertNotNull(overview.files)
    }

    @Test fun missingOrMalformedPartsIndividuallyRequestLegacyFallback() {
        val overview = SessionOverview.read(JSONObject("""{
            "detail":{},"history":{"message":"failed"},"context":null,
            "active_run":{},"activity":{"running":true},"files":{"data":null}
        }"""))
        assertNull(overview.detail)
        assertNull(overview.history)
        assertNull(overview.context)
        assertNull(overview.activeRun)
        assertNull(overview.activity)
        assertNull(overview.files)
    }
}
