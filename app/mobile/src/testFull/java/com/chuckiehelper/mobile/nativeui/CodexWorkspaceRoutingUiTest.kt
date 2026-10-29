package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CodexWorkspaceRoutingUiTest {
    @Test fun ccSwitchCompatibilityMatchDoesNotDisableOtherEndpoints() {
        fun row(id: String, url: String) = JSONObject().put("id", id).put("base_url", url)
        for (url in listOf("http://127.0.0.1:23456/v1", "http://[::1]:9876/v1"))
            assertTrue(isCcSwitchCompatibilityProvider(row("renamed", url).put("compatibility", "cc_switch_managed_proxy")))
        assertFalse(isCcSwitchCompatibilityProvider(row("custom", "http://127.0.0.1:15721/v1")))
        assertFalse(isCcSwitchCompatibilityProvider(row("codebuddy", "http://127.0.0.1:15721/v1")))
        assertFalse(isCcSwitchCompatibilityProvider(row("custom", "http://127.0.0.1:8788/v1")))
        assertFalse(isCcSwitchCompatibilityProvider(row("custom", "https://example.com:15721/v1")))
        assertFalse(isCcSwitchCompatibilityProvider(row("custom", "invalid")))
    }
    @Test fun officialLoginDoesNotMakeAThirdPartyRouteLookSelected() {
        assertFalse(codexOfficialProviderInUse(JSONObject("""{"endpoints":[{"id":"custom","current":true}]}"""),JSONObject("""{"provider":"openai"}""")))
        assertTrue(codexOfficialProviderInUse(JSONObject("""{"endpoints":[{"id":"openai","current":true}]}"""),JSONObject("""{"provider":"custom"}""")))
        assertTrue(codexOfficialProviderInUse(JSONObject(),JSONObject("""{"provider":"openai"}""")))
        assertFalse(codexOfficialProviderInUse(JSONObject(),JSONObject()))
        assertTrue(codexOfficialProviderInUse(JSONObject(),JSONObject("""{"provider":"official_alias","providers":[{"slug":"openai","aliases":["official_alias"],"official_provider":true}]}""")))
    }
    @Test fun providerSnapshotOverridesStaleOptionsAndEndpointFlags() {
        val snapshot = JSONObject("""{"provider":"openai","official_provider":true,"endpoints":[{"id":"custom","current":true}]}""")
        val stale = JSONObject("""{"provider":"custom"}""")
        assertEquals("openai", codexCurrentProviderId(snapshot, stale))
        assertTrue(codexOfficialProviderInUse(snapshot, stale))
        assertTrue(codexOfficialProviderInUse(JSONObject("""{"provider":"renamed_team","official_provider":true}"""), stale))
        assertFalse(codexOfficialProviderInUse(JSONObject("""{"provider":"custom","official_provider":false}"""), JSONObject("""{"provider":"openai"}""")))
    }
    @Test fun missingOfficialCredentialsUsesCredentialMetadataNotLegacyAuthenticationFlag() {
        assertTrue(codexOfficialCredentialsMissing(JSONObject("""{"official_provider":true,"auth_known":true,"auth_kind":"missing","authenticated":true}""")))
        assertFalse(codexOfficialCredentialsMissing(JSONObject("""{"official_provider":true,"auth_known":true,"auth_kind":"openai_login","authenticated":false}""")))
        assertFalse(codexOfficialCredentialsMissing(JSONObject("""{"official_provider":true,"auth_kind":"missing","authenticated":true}""")))
        assertFalse(codexOfficialCredentialsMissing(JSONObject("""{"official_provider":false,"auth_known":true,"auth_kind":"missing"}""")))
    }
}
