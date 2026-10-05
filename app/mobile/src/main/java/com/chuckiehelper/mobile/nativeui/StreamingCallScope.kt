package com.chuckiehelper.mobile.nativeui

import kotlinx.coroutines.*
import okhttp3.Call

/** Cancellation stays connected after headers arrive, including a blocked body read. */
internal suspend fun <T> withStreamingCallCancellation(call: Call, action: suspend () -> T): T = coroutineScope {
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    try { action() } finally { cancellation.cancelAndJoin() }
}
