package com.chuckiehelper.mobile.nativeui

import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.ExperimentalTransitionApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

private val LocalSceneBack = compositionLocalOf { false }
internal val LocalPredictiveBackEnabled = compositionLocalOf { false }
internal const val PREDICTIVE_BACK_KEY = "predictive_back_gesture"

/** A page's root callback yields to its surrounding predictive scene. */
@Composable
internal fun SceneBackHandler(onBack: () -> Unit) {
    BackHandler(enabled = !LocalSceneBack.current, onBack = onBack)
}

/** Both destinations stay composed while seeking; navigation commits only on release. */
@OptIn(ExperimentalTransitionApi::class)
@Composable
internal fun <T : Any> PredictiveBackScene(
    target: T,
    previous: T?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = LocalPredictiveBackEnabled.current,
    content: @Composable (T) -> Unit,
) {
    if (!enabled) {
        BackHandler(enabled = previous != null, onBack = onBack)
        content(target)
        return
    }
    val state = remember { SeekableTransitionState(target) }
    val transition = rememberTransition(state, label = "page back")
    var seeking by remember { mutableStateOf(false) }
    var returning by remember { mutableStateOf(false) }
    val currentTarget by rememberUpdatedState(target)
    val currentBack by rememberUpdatedState(onBack)
    val scope = rememberCoroutineScope()
    var restoration by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(target) {
        if (!seeking) {
            restoration?.cancelAndJoin()
            state.animateTo(target)
            returning = false
        }
    }
    PredictiveBackHandler(enabled = previous != null) { events ->
        val destination = previous ?: return@PredictiveBackHandler
        restoration?.cancelAndJoin()
        restoration = null
        returning = true
        seeking = true
        try {
            events.collect { event -> state.seekTo(event.progress, destination) }
            seeking = false
            currentBack()
        } catch (_: CancellationException) {
            // Keep the same initial/target pages and rewind the fraction.
            // animateTo(origin) reverses the transition's page identities and
            // replaces slide offsets, which makes the page jump horizontally.
            val origin = currentTarget
            restoration = scope.launch {
                val fraction = state.fraction
                val started = withFrameNanos { it }
                var elapsed = 0f
                while (elapsed < 1f) {
                    elapsed = withFrameNanos { ((it - started) / 180_000_000f).coerceIn(0f, 1f) }
                    state.seekTo(fraction * (1f - FastOutSlowInEasing.transform(elapsed)), destination)
                }
                state.snapTo(origin)
                returning = false
            }
        } finally {
            seeking = false
        }
    }
    transition.AnimatedContent(
        modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val back = returning || targetState == previous
            if (back) {
                (slideInHorizontally(tween(220)) { -it / 4 } togetherWith
                    slideOutHorizontally(tween(220)) { it }).apply { targetContentZIndex = -1f }
            } else {
                slideInHorizontally(tween(220)) { it } togetherWith
                    slideOutHorizontally(tween(220)) { -it / 4 }
            }.using(null)
        },
        contentKey = { it },
    ) { page ->
        CompositionLocalProvider(LocalSceneBack provides (previous != null), LocalPredictiveBackEnabled provides enabled) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content(page) }
        }
    }
}
