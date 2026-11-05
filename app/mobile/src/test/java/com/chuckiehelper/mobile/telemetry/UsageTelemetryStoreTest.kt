package com.chuckiehelper.mobile.telemetry

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class UsageTelemetryStoreTest {
    private val prefs get() = RuntimeEnvironment.getApplication().getSharedPreferences("telemetry-test", Context.MODE_PRIVATE)
    private val now = Instant.parse("2026-11-04T15:59:00Z").toEpochMilli()
    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun oldInstallationIsBackfilledAndSurvivesProcessAndRetry() {
        val store = UsageTelemetryStore(prefs)
        store.record(now, true)
        val pending = store.pending()!!
        assertEquals(4, UUID.fromString(pending.getString("installationId")).version())
        assertEquals("2026-11-04", pending.getJSONArray("days").getJSONObject(0).getString("date"))
        val restarted = UsageTelemetryStore(prefs)
        assertEquals(pending.toString(), restarted.pending().toString())
        restarted.acknowledge(pending)
        assertNull(restarted.pending())
        restarted.record(now, false)
        assertNull(restarted.pending())
    }

    @Test fun lateAcknowledgementCannotEraseNewerOpens() {
        val store = UsageTelemetryStore(prefs)
        store.record(now, true)
        val old = store.pending()!!
        store.record(now, true)
        val newer = store.pending()!!
        store.acknowledge(old)
        assertEquals(2, store.pending()!!.getJSONArray("days").getJSONObject(0).getInt("openCount"))
        store.acknowledge(newer)
        store.acknowledge(old)
        assertNull(store.pending())
    }

    @Test fun midnightIsHongKongAndRetentionIsBounded() {
        val store = UsageTelemetryStore(prefs)
        store.record(now, true)
        val id = store.pending()!!.getString("installationId")
        store.record(now + 120_000, false)
        assertEquals("2026-11-05", store.pending()!!.getJSONArray("days").getJSONObject(1).getString("date"))
        assertEquals("Midnight activity is not an extra open", 0,
            store.pending()!!.getJSONArray("days").getJSONObject(1).getInt("openCount"))
        repeat(45) { store.record(now + it * 86_400_000L, true) }
        assertEquals(31, store.pending()!!.getJSONArray("days").length())
        assertEquals(id, store.pending()!!.getString("installationId"))
    }

    @Test fun disablingDropsUnsentCountsButKeepsInstallIdentityAndAcknowledgedBaseline() {
        val store = UsageTelemetryStore(prefs)
        store.record(now, true)
        val first = store.pending()!!
        store.acknowledge(first)
        store.record(now, true)
        store.discardPending()
        assertNull(store.pending())
        store.record(now, true)
        val next = store.pending()!!
        assertEquals(first.getString("installationId"), next.getString("installationId"))
        assertEquals(2, next.getJSONArray("days").getJSONObject(0).getInt("openCount"))
    }
}
