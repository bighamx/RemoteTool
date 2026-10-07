package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first
import kotlin.math.exp

// Time based convergence gives the same motion on 60 Hz and 120 Hz displays.
internal fun chatFollowStep(distance: Float, frameMillis: Float, viewport: Float): Float {
    if (distance <= 0f || viewport <= 0f) return 0f
    val elapsed = frameMillis.coerceIn(1f, 32f)
    val eased = distance * (1f - exp(-elapsed / 85f))
    return eased.coerceAtLeast(0.5f).coerceAtMost(distance)
        .coerceAtMost(viewport * elapsed / 180f)
}

private fun LazyListState.tailDistance(): Float {
    if (!canScrollForward) return 0f
    val layout = layoutInfo
    val tail = layout.visibleItemsInfo.lastOrNull() ?: return 0f
    return if (tail.index == layout.totalItemsCount - 1)
        (tail.offset + tail.size + layout.afterContentPadding - layout.viewportEndOffset)
            .toFloat().coerceAtLeast(0f)
    else (layout.viewportEndOffset - layout.viewportStartOffset).toFloat().coerceAtLeast(0f)
}

/** Follow measured growth, rather than restarting a jump/animation for every SSE delta. */
@Composable
internal fun rememberChatFollowing(
    scroll: LazyListState,
    sessionId: String?,
    list: Boolean,
    hasContent: Boolean,
    sendRequest: Long,
    latestRequest: Long,
): MutableState<Boolean> {
    val following = remember(sessionId, list) { mutableStateOf(true) }
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var wasDragged by remember(sessionId, list) { mutableStateOf(false) }
    var positioned by remember(sessionId, list) { mutableStateOf(false) }
    var handledRequest by remember { mutableStateOf(sendRequest to latestRequest) }

    LaunchedEffect(dragged, sessionId, list) {
        if (dragged) {
            wasDragged = true
            following.value = false
        } else if (wasDragged) {
            // A fling must finish before deciding whether the user returned to the bottom.
            snapshotFlow { scroll.isScrollInProgress }.first { !it }
            following.value = !scroll.canScrollForward
            wasDragged = false
        }
    }
    LaunchedEffect(sendRequest, latestRequest) {
        val request = sendRequest to latestRequest
        if (request == handledRequest) return@LaunchedEffect
        handledRequest = request
        following.value = false
        withFrameNanos { }
        val last = scroll.layoutInfo.totalItemsCount - 1
        if (!list && positioned && last >= 0) scroll.animateScrollToItem(last)
        if (!dragged) following.value = true
    }
    LaunchedEffect(sessionId, list, hasContent) {
        if (list || !hasContent || positioned) return@LaunchedEffect
        withFrameNanos { }
        snapshotFlow { scroll.layoutInfo.totalItemsCount }.first { it > 0 }
        scroll.scrollToItem(scroll.layoutInfo.totalItemsCount - 1)
        positioned = true
    }
    LaunchedEffect(scroll, sessionId, list, following.value, dragged, positioned) {
        if (list || !following.value || dragged || !positioned) return@LaunchedEffect
        while (true) {
            // Suspend while idle: no frame polling or perpetual animation when nothing grows.
            snapshotFlow { scroll.tailDistance() }.first { it > 0f }
            var previous = withFrameNanos { it }
            while (scroll.canScrollForward) {
                val now = withFrameNanos { it }
                val distance = scroll.tailDistance()
                if (distance <= 0f) break
                val viewport = (scroll.layoutInfo.viewportEndOffset - scroll.layoutInfo.viewportStartOffset).toFloat()
                val step = chatFollowStep(distance, (now - previous) / 1_000_000f, viewport)
                previous = now
                if (step <= 0f || scroll.scrollBy(step) == 0f) break
            }
        }
    }
    return following
}
