package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerBuildVersionTest {
    @Test fun versionIsOptionalForOlderServersAndRejectsInvalidValues() {
        assertEquals("20261028.061234567",JSONObject("""{"serverVersion":"20261028.061234567"}""").serverBuildVersion())
        assertNull(JSONObject().serverBuildVersion())
        assertNull(JSONObject().put("serverVersion",JSONObject.NULL).serverBuildVersion())
        assertNull(JSONObject().put("serverVersion",123).serverBuildVersion())
        assertNull(JSONObject().put("serverVersion","x".repeat(81)).serverBuildVersion())
        assertNull(JSONObject().put("serverVersion","a\nb").serverBuildVersion())
    }
}
