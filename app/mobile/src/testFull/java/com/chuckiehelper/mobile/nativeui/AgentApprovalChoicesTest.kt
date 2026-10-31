package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray

class AgentApprovalChoicesTest {
    @Test fun oldServersKeepSingleApprovalAndNewServersExposeOnlyTheirChoices() {
        assertEquals(listOf("once", "deny"), agentApprovalChoices(obj()).map { it.id })
        val request = obj("choices" to JSONArray(listOf(
            obj("id" to "once", "label" to "仅允许本次"),
            obj("id" to "session", "label" to "本会话允许"),
            obj("id" to "network:1", "label" to "总是允许联网", "detail" to "api.nuget.org"))))
        val choices = agentApprovalChoices(request)
        assertEquals(listOf("once", "session", "network:1"), choices.map { it.id })
        assertEquals("api.nuget.org", choices.last().detail)
        assertFalse(choices.any { it.id == "full" || it.id == "always" })
    }
}
