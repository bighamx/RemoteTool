package com.chuckiehelper.mobile.nativeui

import org.junit.Assert.*
import org.junit.Test

class ProtocolAddressTest {
    @Test fun splitAndPastePreserveProtocolIpv6PortAndCustomServices() {
        val protocols=listOf("http","https","ssh")
        assertEquals(ProtocolAddress("https","[::1]:8443"),splitProtocolAddress("https://[::1]:8443",protocols,true))
        assertEquals(ProtocolAddress("","unix:/var/run/service.sock"),splitProtocolAddress("unix:/var/run/service.sock",protocols,true))
        assertEquals(ProtocolAddress("","http_status:404"),splitProtocolAddress("http_status:404",protocols,true))
        assertEquals("https://host:443",joinProtocolAddress("http","https://host:443"))
        assertEquals("ssh://localhost:22",joinProtocolAddress("ssh","localhost:22"))
        assertEquals("http_status:404",joinProtocolAddress("","http_status:404"))
    }
    @Test fun deviceAddressesRetainRootValidationAndReplaceWithoutDuplicates() {
        assertEquals("https://[::1]:8888",normalizedDeviceAddress(" https://[::1]:8888/ "))
        assertEquals(listOf("https://new","http://backup"),replacedDeviceAddresses(listOf("http://old","http://backup","https://new"),"http://old","https://new"))
        for(value in listOf("ftp://host","http://user:secret@host","http://host/path","http://host?q=1","http://host#part")) {
            try { normalizedDeviceAddress(value);fail("invalid address accepted") } catch (_: IllegalArgumentException) { }
        }
        try { replacedDeviceAddresses(listOf("http://new"),"http://old","http://other");fail("stale edit accepted") } catch (_: IllegalArgumentException) { }
    }
}
