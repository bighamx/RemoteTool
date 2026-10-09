package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.composed
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration

/** Padding taps only. Child text selection, links, attachments and scrolling win. */
internal fun Modifier.bubbleTap(onTap: () -> Unit): Modifier = composed {
    val current = rememberUpdatedState(onTap)
    val config = LocalViewConfiguration.current
    pointerInput(config) {
        awaitEachGesture {
            val first = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
            if (first.isConsumed) return@awaitEachGesture
            val tap = BubbleTapGesture(config.touchSlop, config.longPressTimeoutMillis)
            tap.down(first.position.x, first.position.y, first.uptimeMillis)
            do {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == first.id } ?: break
                if (event.changes.size != 1 || event.changes.any { it.isConsumed }) tap.cancel()
                tap.move(change.position.x, change.position.y)
                if (!change.pressed && tap.up(change.position.x, change.position.y, change.uptimeMillis)) current.value()
            } while (event.changes.any { it.pressed })
        }
    }
}
