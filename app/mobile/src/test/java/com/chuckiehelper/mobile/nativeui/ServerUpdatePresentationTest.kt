package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ServerUpdatePresentationTest {
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
