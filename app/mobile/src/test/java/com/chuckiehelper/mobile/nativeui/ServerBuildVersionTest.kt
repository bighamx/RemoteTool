package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerBuildVersionTest {
    @Test fun protocolPrecedesBuildAndOlderServersStillDisplayBuild() {
        assertEquals("协议 v1 · 编译 20261029.0631",JSONObject("""{"protocolVersion":1,"serverVersion":"20261029.0631"}""").serverVersionDescription())
        assertEquals("编译 20261029.0631",JSONObject("""{"serverVersion":"20261029.0631"}""").serverVersionDescription())
        assertEquals("协议 v2",JSONObject("""{"protocolVersion":2}""").serverVersionDescription())
        for (invalid in listOf(0, -1, 1.5, "1", JSONObject.NULL)) {
            assertNull(JSONObject().put("protocolVersion",invalid).serverVersionDescription())
        }
    }
    @Test fun versionIsOptionalForOlderServersAndRejectsInvalidValues() {
        assertEquals("20261028.061234567",JSONObject("""{"serverVersion":"20261028.061234567"}""").serverBuildVersion())
        assertNull(JSONObject().serverBuildVersion())
        assertNull(JSONObject().put("serverVersion",JSONObject.NULL).serverBuildVersion())
        assertNull(JSONObject().put("serverVersion",123).serverBuildVersion())
        assertNull(JSONObject().put("serverVersion","x".repeat(81)).serverBuildVersion())
        assertNull(JSONObject().put("serverVersion","a\nb").serverBuildVersion())
    }
}
