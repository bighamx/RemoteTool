package com.chuckiehelper.mobile.nativeui

/** Pauses only local observation; the server task and its saved run ID remain intact. */
internal class ForegroundObservation(private val start: () -> Unit, private val stop: () -> Unit) {
    var active: Boolean = false
        private set
    fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        if (value) start() else stop()
    }
}

internal fun agentExternalPollDelay(active: Boolean): Long = if (active) 2_000 else 10_000
internal fun agentContextPollDelay(active: Boolean): Long = if (active) 10_000 else 30_000
internal fun agentSessionPollDelay(active: Boolean): Long = if (active) 5_000 else 15_000
internal fun agentUsagePollDelay(active: Boolean): Long = if (active) 60_000 else 120_000
internal fun agentStatusPollDelay(streamConnected: Boolean, failures: Int): Long =
    if (failures > 0) (5_000L * (1L shl (failures - 1).coerceIn(0, 3))).coerceAtMost(30_000)
    else if (streamConnected) 5_000 else 2_000
