package com.chuckiehelper.mobile.nativeui

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DeviceAddressSelectionTest {
    private val lan = "http://192.168.1.224:8888"
    private val ipv6 = "http://homev6.xbyham.com:8888"
    private val https = "https://home-manage.xbyham.com"
    private val addresses = listOf(lan, ipv6, https)

    @Test fun unavailableLanFallsThroughWithoutDeletingAnyAddress() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = selectVerifiedDeviceAddress(deviceAddressCandidates(addresses, lan), "home") { url ->
            attempted += url
            if (url == lan) throw IOException("unreachable")
            "home" to url
        }
        assertEquals(ipv6, result.second)
        assertEquals(listOf(lan, ipv6), attempted)
        assertEquals(3, addresses.size)
    }
    @Test fun httpsWorksWhenBothEarlierAddressesFail() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = selectVerifiedDeviceAddress(addresses, "home") { url ->
            attempted += url
            if (url != https) throw IOException("timeout")
            "home" to url
        }
        assertEquals(https, result.second)
        assertEquals(addresses, attempted)
        // The selected URL, rather than the original LAN URL, is what gets remembered.
        assertEquals(listOf(https, lan, ipv6), deviceAddressCandidates(addresses, result.second))
    }
    @Test fun successfulPreviousAddressHasPriority() = runBlocking {
        val attempted = mutableListOf<String>()
        val result = selectVerifiedDeviceAddress(deviceAddressCandidates(addresses, https), "home") { url ->
            attempted += url; "home" to url
        }
        assertEquals(https, result.second)
        assertEquals(listOf(https), attempted)
    }
    @Test fun deletedPreferenceAndDuplicatesAreExcluded() {
        assertEquals(listOf(ipv6, https), deviceAddressCandidates(listOf(ipv6, https, ipv6), lan))
    }
    @Test fun wrongDeviceNeverGetsSelectedForAuthentication() = runBlocking {
        val result = selectVerifiedDeviceAddress(addresses, "home") { url ->
            (if (url == lan) "another-device" else "home") to url
        }
        assertEquals("home" to ipv6, result)
    }
    @Test fun allFailuresReportEveryAttempt() = runBlocking {
        try {
            selectVerifiedDeviceAddress<String>(addresses, "home") { throw IOException("unreachable") }
            fail("Expected connection failure")
        } catch (error: DeviceAddressFailure) {
            assertEquals(addresses, error.attempts.map { it.first })
            assertTrue(error.message!!.contains(https))
        }
    }
    @Test fun cancellationStopsBeforeTryingAnotherAddress() = runBlocking {
        val attempted = mutableListOf<String>()
        try {
            selectVerifiedDeviceAddress<String>(addresses, "home") { url -> attempted += url; throw CancellationException("cancel") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertEquals(listOf(lan), attempted) }
    }
    @Test fun manualSelectionDoesNotSilentlyChangeAddress() = runBlocking {
        val attempted = mutableListOf<String>()
        try {
            selectVerifiedDeviceAddress<String>(listOf(lan), "home") { url -> attempted += url; throw IOException("unreachable") }
            fail("Expected failure")
        } catch (error: IOException) {
            assertEquals("unreachable", error.message)
            assertEquals(listOf(lan), attempted)
        }
    }
}
