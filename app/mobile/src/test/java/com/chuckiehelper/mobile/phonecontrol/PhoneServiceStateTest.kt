package com.chuckiehelper.mobile.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class PhoneServiceStateTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        PhoneControlService.stop(context)
        context.getSharedPreferences("phone-tools", 0).edit().clear().commit()
    }
    @After fun cleanup() { PhoneControlService.stop(context) }

    @Test fun preferencesSurviveRecreationButServiceAdmissionDoesNot() {
        PhoneSettings(context).apply {
            direct = false; advanced = true; root = true; shizuku = false
            port = 18792; address = "192.0.2.8"; connectionTab = "web"
            setRelayEnabled("https://server-a", true)
            enabled = true
        }
        PhoneSettings(context).apply {
            assertFalse(enabled)
            assertFalse(direct); assertTrue(advanced); assertTrue(root); assertFalse(shizuku)
            assertEquals(18792, port); assertEquals("192.0.2.8", address)
            assertEquals("web", connectionTab)
            assertTrue(relayEnabled("https://server-a"))
            assertFalse(relayEnabled("https://server-b"))
        }
    }

    @Test fun legacyWebOnlySelectionMigratesOnceWithoutStartingService() {
        context.getSharedPreferences("phone-tools", 0).edit()
            .putBoolean("enabled", true).putBoolean("direct", false).commit()
        val settings = PhoneSettings(context)
        assertFalse(settings.enabled)
        assertTrue(settings.relayEnabled("https://server-a"))
        assertFalse(settings.relayEnabled("https://server-b"))
        settings.setRelayEnabled("https://server-a", false)
        assertFalse(PhoneSettings(context).relayEnabled("https://server-a"))
        assertFalse(PhoneControlService.starting)
        assertFalse(PhoneControlService.running)
    }

    @Test fun legacyDirectModeDoesNotSelectWebRelay() {
        context.getSharedPreferences("phone-tools", 0).edit().putBoolean("enabled", true).commit()
        assertFalse(PhoneSettings(context).relayEnabled("https://server-a"))
    }

    @Test fun foregroundStartRejectionClearsStartingAndAdmission() {
        val blocked = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? =
                throw IllegalStateException("start blocked")
        }
        try { PhoneControlService.start(blocked); fail("must propagate startup failure") }
        catch (e: IllegalStateException) { assertEquals("start blocked", e.message) }
        assertFalse(PhoneControlService.starting)
        assertFalse(PhoneControlService.running)
        assertFalse(PhoneEngine.get(context).settings.enabled)
        assertEquals("start blocked", PhoneControlService.error)
    }

    @Test fun staleDiskFlagCannotResurrectServiceOnNullIntent() {
        context.getSharedPreferences("phone-tools", 0).edit().putBoolean("enabled", true).commit()
        val controller = Robolectric.buildService(PhoneControlService::class.java).create()
        try {
            controller.get().onStartCommand(null, 0, 1)
            assertFalse(PhoneControlService.running)
            assertFalse(PhoneControlService.starting)
            assertFalse(PhoneEngine.get(context).settings.enabled)
        } finally { controller.destroy() }
    }
    @Test fun autoRestoreNeedsOptInAndPreviousRunningIntentAndHonorsManualStop() {
        val settings = PhoneSettings(context)
        val attempts = java.util.concurrent.atomic.AtomicInteger()
        val blocked = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? { attempts.incrementAndGet(); throw IllegalStateException("background start blocked") }
        }
        settings.desiredRunning = true
        com.chuckiehelper.mobile.startup.PhoneAutoRestore.request(blocked)
        assertEquals(0, attempts.get())
        settings.autoRestore = true; settings.desiredRunning = false
        com.chuckiehelper.mobile.startup.PhoneAutoRestore.request(blocked)
        assertEquals(0, attempts.get())
        settings.desiredRunning = true
        com.chuckiehelper.mobile.startup.PhoneAutoRestore.request(blocked)
        assertEquals(1, attempts.get())
        assertTrue(settings.desiredRunning)
        PhoneControlService.stop(context)
        assertFalse(PhoneSettings(context).desiredRunning)
        com.chuckiehelper.mobile.startup.PhoneAutoRestore.request(blocked)
        assertEquals(1, attempts.get())
    }
}
