package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import kotlinx.coroutines.flow.first

private class ChatFollowAnchor {
    var initialized = false
    var session: String? = null
    var list = false
    var newestKey: String? = null
    var send = 0L
    var latest = 0L
}

/** Reverse layout anchors growth in the same measure pass; no after-layout scroll chase. */
@Composable
internal fun rememberChatFollowing(
    scroll: LazyListState,
    sessionId: String?,
    list: Boolean,
    hasContent: Boolean,
    sendRequest: Long,
    latestRequest: Long,
    newestKey: String?,
): MutableState<Boolean> {
    val following = remember(sessionId, list) { mutableStateOf(true) }
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var wasDragged by remember(sessionId, list) { mutableStateOf(false) }
    val anchor = remember(scroll) { ChatFollowAnchor() }

    LaunchedEffect(dragged, sessionId, list) {
        if (dragged) {
            wasDragged = true
            following.value = false
        } else if (wasDragged) {
            snapshotFlow { scroll.isScrollInProgress }.first { !it }
            following.value = !scroll.canScrollBackward
            wasDragged = false
        }
    }
    SideEffect {
        val explicit = anchor.initialized && (anchor.send != sendRequest || anchor.latest != latestRequest)
        val changed = !anchor.initialized || anchor.session != sessionId || anchor.list != list || anchor.newestKey != newestKey
        if (!list && hasContent && !dragged && (explicit || following.value && changed)) {
            // Request before the next measure, including history swaps/new rows.
            // Resizing an existing row needs no request: index zero stays at the bottom.
            scroll.requestScrollToItem(0)
            if (explicit) following.value = true
        }
        anchor.initialized = true
        anchor.session = sessionId; anchor.list = list; anchor.newestKey = newestKey
        anchor.send = sendRequest; anchor.latest = latestRequest
    }
    return following
}
