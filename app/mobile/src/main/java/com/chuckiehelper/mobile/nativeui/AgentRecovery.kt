package com.chuckiehelper.mobile.nativeui

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay

internal fun canRecoverAgentRead(error: Exception): Boolean = when (error) {
    is TimeoutCancellationException -> true
    is CancellationException, is LoginRequired -> false
    is ApiRequestFailure -> error.status in setOf(408, 502, 503, 504) ||
        error.code == "account_changed" || staleRunFailure(error)
    is IOException -> true
    else -> false
}

/** Only pass reads here. A failed write is reconciled by reading, never replayed. */
internal suspend fun <T> retryAgentRead(
    pause: suspend (Long) -> Unit = { delay(it) },
    read: suspend () -> T,
): T {
    for (attempt in 0..3) {
        if (attempt > 0) pause(1_000L shl (attempt - 1))
        try { return read() }
        catch (error: Exception) {
            if (!canRecoverAgentRead(error) || attempt == 3) throw error
        }
    }
    error("Unreachable")
}
