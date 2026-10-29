package com.chuckiehelper.mobile.nativeui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CloudflareTunnelConnectionTest {
    @Test fun nestedConnectorUsesParentIdentityAndAllEdges() {
        val info = cloudflareTunnelConnection(JSONObject("""{"id":"connector","version":"2026.10.0","arch":"windows_amd64","clientId":null,"coloName":null,"originIp":null,"conns":[{"id":"edge1","colo_name":"HKG","origin_ip":"203.0.113.1"},{"id":"edge2","colo_name":"NRT","origin_ip":"203.0.113.1"}]}"""))
        assertEquals("connector", info.clientId)
        assertEquals("HKG、NRT", info.locations)
        assertEquals("203.0.113.1", info.originIps)
        assertEquals("2026.10.0", info.version)
        assertEquals("windows_amd64", info.arch)
    }
    @Test fun flatLegacyConnectionUsesClientIdNotEdgeId() {
        val info = cloudflareTunnelConnection(JSONObject("""{"id":"edge","clientId":"connector","coloName":"HKG","originIp":"203.0.113.1","clientVersion":"2026.9.0"}"""))
        assertEquals("connector", info.clientId)
        assertEquals("HKG", info.locations)
        assertEquals("2026.9.0", info.version)
    }
    @Test fun missingFieldsNeverBecomeLiteralNullOrDisconnectEdge() {
        val info = cloudflareTunnelConnection(JSONObject("""{"id":"edge","clientId":null,"coloName":null,"originIp":"null","clientVersion":null}"""))
        assertEquals(CloudflareTunnelConnection("", "", "", "", ""), info)
    }
    @Test fun emptyConnectorRetainsDisconnectIdentity() {
        val info = cloudflareTunnelConnection(JSONObject("""{"id":"connector","conns":[],"version":"2026.10.0"}"""))
        assertEquals("connector", info.clientId)
        assertEquals("", info.locations)
    }
}
