package com.chuckiehelper.mobile.nativeui

/** Holding an idle writer is compatible with desktop cooperation, not a task to interrupt. */
internal fun showWriteInterruption(externalRunning: Boolean, controlState: String?, verifiedAt: Long, now: Long): Boolean =
    externalRunning || (controlState == "busy" && verifiedAt > 0 && now - verifiedAt in 0 until 30_000L)
