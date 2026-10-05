package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentModelSettingsTest {
    private val provider = JSONObject("""{"models":[{"id":"display-id","model":"m","supportedReasoningEfforts":[{"reasoningEffort":"low"},{"reasoningEffort":"max"}],"serviceTiers":[{"id":"priority","name":"Fast"}]}]}""")
    @Test fun codexUsesAdvertisedEffortsAndActualFastTierId() {
        assertEquals(listOf("low", "max"), reasoningChoices("codex", provider, "m"))
        assertEquals("priority", fastTier(provider, "m"))
        assertTrue(reasoningChoices("codex", provider, "unknown").isEmpty())
        assertNull(fastTier(provider, "unknown"))
    }
    @Test fun hermesHonorsMandatoryReasoningAndNonReasoningModels() {
        val p = JSONObject("""{"capabilities":{"m":{"reasoning":true,"can_disable_reasoning":false},"plain":{"reasoning":false}}}""")
        assertFalse("none" in reasoningChoices("hermes", p, "m"))
        assertTrue(reasoningChoices("hermes", p, "plain").isEmpty())
        assertEquals(false, hermesReasoning("none").getBoolean("enabled"))
        assertEquals("high", hermesReasoning("high").getString("effort"))
        assertEquals(0, hermesReasoning("").length())
    }
    @Test fun hermesPersistedLockWinsOverOldModelAndStringConfigIsParsed() {
        val session = JSONObject("""{"provider":"old","model":"old-model","model_config":{"browser_model_lock":{"provider":"new","model":"new-model","model_options":{"reasoning":{"enabled":false}}}}}""")
        assertEquals(AgentSelection("new", "new-model", "none", ""), readAgentSelection(session))
        session.put("model_config", session.getJSONObject("model_config").toString())
        assertEquals("new", readAgentSelection(session).provider)
    }
    @Test fun nullMetadataDoesNotBecomeLiteralNull() {
        assertEquals(AgentSelection("", "", "", ""), readAgentSelection(JSONObject("""{"model":null,"provider":null,"reasoning_effort":null,"service_tier":null}""")))
    }
}
