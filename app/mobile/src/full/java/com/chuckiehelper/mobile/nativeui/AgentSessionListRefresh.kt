@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.widget.ImageView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.pullToRefreshIndicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable

@Composable
internal fun AgentSessionListRefresh(
    listState: LazyListState,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val pullState = rememberPullToRefreshState()
    Box(modifier.pullToRefresh(
        isRefreshing = isRefreshing,
        state = pullState,
        enabled = enabled && !isRefreshing && !listState.canScrollBackward,
        onRefresh = onRefresh,
    )) {
        content()
        Box(
            Modifier.align(Alignment.TopCenter).size(32.dp).pullToRefreshIndicator(
                state = pullState,
                isRefreshing = isRefreshing,
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                elevation = 2.dp,
            ),
            contentAlignment = Alignment.Center,
        ) {
            SessionRefreshArrow(isRefreshing, { pullState.distanceFraction })
        }
    }
}

/** The same standard drawable used by aiwei's SwipeRefreshLayout. */
@Composable
private fun SessionRefreshArrow(refreshing: Boolean, distance: () -> Float) {
    val context = LocalContext.current
    val color = MaterialTheme.colorScheme.primary.toArgb()
    val drawable = remember(context) {
        CircularProgressDrawable(context).apply { setStyle(CircularProgressDrawable.DEFAULT) }
    }
    DisposableEffect(drawable) { onDispose { drawable.stop() } }
    AndroidView(
        factory = { ImageView(it).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageDrawable(drawable)
        } },
        modifier = Modifier.size(24.dp),
        update = { view ->
            drawable.setColorSchemeColors(color)
            view.contentDescription = if (refreshing) "正在刷新" else "下拉刷新"
            if (refreshing) {
                if (!drawable.isRunning) drawable.start()
            } else {
                if (drawable.isRunning) drawable.stop()
                val progress = ((distance().coerceIn(0f, 1f) - .4f).coerceAtLeast(0f) * 5f / 3f).coerceIn(0f, 1f)
                drawable.setArrowEnabled(true)
                drawable.setArrowScale(progress)
                drawable.setStartEndTrim(0f, progress * .8f)
                drawable.setProgressRotation((-.25f + progress * .4f) * .5f)
            }
        },
    )
}
