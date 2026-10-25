package com.chuckiehelper.mobile.phonecontrol

import org.junit.Assert.*
import org.junit.Test

class HyperIslandSupportTest {
    @Test fun os2PayloadDoesNotClaimIslandAndLongTextStaysWithinTheOfficialBudget() {
        val params = HyperIslandSupport.parameters("等待协助", "需要协助".repeat(1000), true, false)
        val payload = params.getJSONObject("param_v2")
        assertFalse(payload.has("param_island"))
        assertTrue(payload.getBoolean("enableFloat"))
        assertFalse(payload.getBoolean("filterWhenNoPermission"))
        assertTrue(params.toString().toByteArray(Charsets.UTF_8).size <= 3072)
    }
    @Test fun os3IncludesBigSmallIslandAndARegularNotificationFallback() {
        val payload = HyperIslandSupport.parameters("Agent 操作", "正在读取界面", false, true).getJSONObject("param_v2")
        val island = payload.getJSONObject("param_island")
        assertTrue(island.has("bigIslandArea")); assertTrue(island.has("smallIslandArea"))
        assertFalse(payload.getBoolean("enableFloat"))
        assertEquals("正在读取界面", payload.getJSONObject("baseInfo").getString("content"))
    }
}
