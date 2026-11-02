package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class ServerUpdatePresentationTest {
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
