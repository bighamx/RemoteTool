package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ServerUpdatePresentationTest {
    @Test fun sourcePrimaryActionMatchesTheActualNextStep() {
        val value=JSONObject("""{"mode":"source","canRebuild":true,"supportsSourcePull":true,"currentVersion":"running","check":{"kind":"source","runningVersion":"running"}}""")
        val check=value.getJSONObject("check")
        check.put("state","rebuild_required")
        assertEquals(SourceUpdateAction.COMPILE,sourceUpdateAction(value))
        assertEquals("源码已是最新，需编译并重启服务端",updateCheckText(value))
        check.put("state","source_changed")
        assertEquals(SourceUpdateAction.COMPILE,sourceUpdateAction(value))
        check.put("state","source_out_of_sync")
        assertEquals(SourceUpdateAction.PULL,sourceUpdateAction(value))
        check.put("state","up_to_date")
        assertNull(sourceUpdateAction(value))
        check.put("state","source_out_of_sync").put("runningVersion","old")
        assertNull(sourceUpdateAction(value))
        check.put("runningVersion","running").put("error","offline")
        assertNull(sourceUpdateAction(value))
        value.remove("check")
        assertNull(sourceUpdateAction(value))
        value.put("supportsSourcePull",false)
        assertEquals(SourceUpdateAction.COMPILE,sourceUpdateAction(value))
        value.put("canRebuild",false)
        assertNull(sourceUpdateAction(value))
    }
    @Test fun activeAndTerminalJobProgressMatchesServerPhases() {
        for (phase in listOf("requested","waiting","pulling","building","downloading","switching","verifying","rolling_back","migrating")) {
            val job=JSONObject().put("phase",phase)
            assertTrue(phase,updateJobActive(job))
            assertTrue(phase,updateJobTitle(job).isNotBlank())
        }
        for (phase in listOf("completed","deferred","rolled_back","failed","downloaded","recovery_required")) {
            val job=JSONObject().put("phase",phase)
            assertFalse(phase,updateJobActive(job))
            assertTrue(phase,updateJobTitle(job).isNotBlank())
        }
        assertFalse(updateJobActive(null))
        assertEquals("",updateJobTitle(null))
        assertEquals("等待任务空闲",updateJobTitle(JSONObject().put("phase","waiting")))
    }
    @Test fun latestAndUnknownSourceStateAreExplicit() {
        val value=JSONObject("""{"mode":"source","check":{"kind":"source","state":"up_to_date","message":"已是最新版本"}}""")
        assertEquals("已是最新版本",updateCheckText(value))
        assertEquals("正在检查更新…",updateCheckText(value,true))
        value.put("check",JSONObject("""{"available":false}"""))
        assertEquals("尚未核对源码版本，请点击立即检查",updateCheckText(value))
        value.put("check",JSONObject("""{"kind":"source","error":"network unavailable"}"""))
        assertTrue(updateCheckText(value).startsWith("检查失败："))
        value.put("mode","release").put("check",JSONObject("""{"available":false,"error":null}"""))
        assertEquals("已是最新版本",updateCheckText(value))
    }
    @Test fun jsonNullAndMissingFieldsNeverBecomeLiteralNull() {
        val value = JSONObject("""{"error":null,"notes":"null","message":"等待空闲"}""")
        assertEquals("", value.updateText("error"))
        assertEquals("", value.updateText("notes"))
        assertEquals("", value.updateText("missing"))
        assertEquals("等待空闲", value.updateText("message"))
    }
    @Test fun installationWindowIsShownInLocalTime() {
        val policy = JSONObject("""{"startHourUtc":18,"endHourUtc":22}""")
        assertEquals("自动安装时段：02:00–06:00（本地时间）", updateWindowText(policy, ZoneId.of("Asia/Hong_Kong")))
        assertEquals("自动安装时段：23:30–03:30（本地时间）", updateWindowText(policy, ZoneId.of("Asia/Kolkata")))
        assertEquals("自动安装时段：全天", updateWindowText(JSONObject("""{"startHourUtc":18,"endHourUtc":18}""")))
    }
    @Test fun requestFailureRetainsHttpCodeWithReadableText() {
        assertEquals("更新失败；服务端请求失败（HTTP 503）", updateErrorText("更新失败；Response status code does not indicate success: 503 (Service Unavailable)."))
    }
}
