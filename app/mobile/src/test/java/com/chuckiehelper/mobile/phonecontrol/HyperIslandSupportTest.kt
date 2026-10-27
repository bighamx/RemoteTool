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
    @Test fun summarySeparatesAgentFromDynamicActionAndPreservesExpandedDetail() {
        val payload = HyperIslandSupport.parameters("Codex · 正在读取界面", "查询商品并核对价格", false, true,
            "Codex", "读取界面").getJSONObject("param_v2")
        val big = payload.getJSONObject("param_island").getJSONObject("bigIslandArea")
        assertEquals("Codex", big.getJSONObject("imageTextInfoLeft").getJSONObject("textInfo").getString("title"))
        assertFalse(big.getJSONObject("imageTextInfoLeft").has("picInfo"))
        assertEquals("读取界面", big.getJSONObject("textInfo").getString("title"))
        assertEquals("查询商品并核对价格", payload.getJSONObject("baseInfo").getString("content"))
        val state = AgentDisplayState(true, "Codex", true, "正在输入文字", null)
        assertEquals("输入文字", PhoneAgentStatus.shortText(state))
        assertEquals("等待协助", PhoneAgentStatus.shortText(state.copy(
            notice = AgentUserNotice("notice", "owner", "Codex", "请授权", true, 999))))
        assertEquals("中文名称", PhoneAgentStatus.islandText("中文名称过长"))
        assertEquals("Codex123", PhoneAgentStatus.islandText("Codex123456"))
    }

    @Test fun actionUsesSixChineseGlyphsAndSmallIslandKeepsItsIcon() {
        val payload = HyperIslandSupport.parameters("Hermes · 正在读写系统设置", "完整的操作说明", false, true,
            "Hermes", "读写系统设置并核对").getJSONObject("param_v2")
        val island = payload.getJSONObject("param_island")
        assertEquals("读写系统设置", island.getJSONObject("bigIslandArea").getJSONObject("textInfo").getString("title"))
        assertTrue(island.getJSONObject("smallIslandArea").has("picInfo"))
        assertEquals("完整的操作说明", payload.getJSONObject("baseInfo").getString("content"))
        assertEquals("ab😀中文cd", PhoneAgentStatus.islandText("ab😀中文cd长", maxUnits = 10))
    }
}
