package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A bounded live viewport; event updates never scroll the surrounding conversation. */
@Composable
internal fun ExecutionActivityPreview(events: List<HermesEvent>, codex: Boolean, expanded: Boolean = false) {
    val content = remember(events, codex) {
        events.takeLast(30).joinToString("\n") { event ->
            val detail = (if (codex) compactCodexToolPreview(event.detail) else event.detail).trim()
            listOf(event.type.trim(), event.text.trim(), detail.take(200))
                .filter { it.isNotBlank() }.joinToString(" · ")
        }.ifBlank { "等待操作进度…" }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(content, scroll.maxValue) {
        scroll.animateScrollTo(scroll.maxValue)
    }
    // Reuse the space reclaimed from the separate title/footer and two-line timers.
    // Bound the growth for large system fonts as well as the normal ten-line viewport.
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val compactHeight = with(LocalDensity.current) {
        minOf(18.sp.toDp() * 10, 20.sp.toDp() * 4 + 100.dp)
    }
    val viewportHeight = if (expanded) maxOf(compactHeight, screenHeight * 0.5f) else compactHeight
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(max = viewportHeight).clipToBounds().verticalScroll(scroll)) {
                Text(content, style = MaterialTheme.typography.bodySmall.copy(
                    lineHeight = 18.sp, platformStyle = PlatformTextStyle(includeFontPadding = false)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
