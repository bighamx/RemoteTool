package com.chuckiehelper.mobile.nativeui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep child failures inside the operation's error boundary, including late completions. */
internal suspend fun runAgentOperation(
    onFailure: (Exception) -> Unit,
    block: suspend CoroutineScope.() -> Unit,
) {
    try {
        coroutineScope(block)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        currentCoroutineContext().ensureActive()
        onFailure(failure)
    }
}
