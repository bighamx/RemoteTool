@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import org.json.JSONObject

@Composable
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
fun MediaViewer(api: NativeApi, file: JSONObject, close: () -> Unit, download: () -> Unit) {
    val context = LocalContext.current
    val path = file.optString("path")
    val image =
        file.optString("name").substringAfterLast('.').lowercase() in
            listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    val video = file.optString("mime").startsWith("video/") ||
        file.optString("name").substringAfterLast('.').lowercase() in listOf("mp4", "mkv", "webm", "mov", "avi", "wmv", "m4v")
    val mediaUrl = api.base + file.optString("url").ifBlank { "/api/files/stream?path=${q(path)}" }
    var fullscreen by remember(mediaUrl) { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var error by remember { mutableStateOf<String?>(null) }
    var playing by remember(mediaUrl) { mutableStateOf(false) }
    val imageLoader =
        remember(context) { ImageLoader.Builder(context).okHttpClient(NativeApi.client).build() }
    // The player lives outside the normal/fullscreen layout: toggling never reloads
    // the URL, resets the position, or changes the user's paused state.
    val player = if (image) null else remember(api, mediaUrl) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(NativeApi.client)
                .setDefaultRequestProperties(mapOf("Cookie" to api.cookie()))))
            .build().apply {
                setMediaItem(MediaItem.fromUri(mediaUrl))
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlayerError(e: PlaybackException) {
                        error = "手机无法解码此格式，可下载后使用兼容的播放器打开。\n不进行服务端转码。"
                    }
                })
                prepare(); playWhenReady = true
            }
    }
    DisposableEffect(player) { onDispose { player?.release() } }
    val activity = remember(context) { context.mediaActivity() }
    DisposableEffect(activity, fullscreen) {
        val previousOrientation = activity?.requestedOrientation
        if (fullscreen) {
            val size = player?.videoSize
            activity?.requestedOrientation = if (size != null && size.height > size.width * size.pixelWidthHeightRatio)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose { if (fullscreen && previousOrientation != null) activity?.requestedOrientation = previousOrientation }
    }
    Dialog(
        onDismissRequest = { if (fullscreen) fullscreen = false else close() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !fullscreen),
    ) {
        MediaFullscreenWindow(fullscreen)
        Scaffold(
            contentWindowInsets = if (fullscreen) WindowInsets(0, 0, 0, 0) else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (!fullscreen)
                TopAppBar(
                    title = { Text(file.optString("name"), maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭") }
                    },
                    actions = {
                        if (video) IconButton(onClick = { fullscreen = true }) { Icon(Icons.Outlined.Fullscreen, "全屏播放") }
                        if (image)
                            TextButton(
                                onClick = {
                                    zoom = 1f
                                    pan = Offset.Zero
                                }
                            ) {
                                Text("适应")
                            }
                        TextButton(onClick = download) { Text("下载") }
                    },
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).background(Color.Black)) {
                if (image)
                    AsyncImage(
                        model =
                            ImageRequest.Builder(context)
                                .data(
                                    api.base +
                                        file.optString("url").ifBlank {
                                            "/api/files/preview-image?path=${q(path)}"
                                        }
                                )
                                .addHeader("Cookie", api.cookie())
                                .crossfade(true)
                                .build(),
                        imageLoader = imageLoader,
                        contentDescription = file.optString("name"),
                        contentScale = ContentScale.Fit,
                        modifier =
                            Modifier.fillMaxSize()
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, delta, scale, _ ->
                                        zoom = (zoom * scale).coerceIn(1f, 6f)
                                        pan =
                                            if (zoom == 1f) Offset.Zero
                                            else
                                                Offset(
                                                    (pan.x + delta.x).coerceIn(
                                                        -size.width * (zoom - 1) / 2,
                                                        size.width * (zoom - 1) / 2,
                                                    ),
                                                    (pan.y + delta.y).coerceIn(
                                                        -size.height * (zoom - 1) / 2,
                                                        size.height * (zoom - 1) / 2,
                                                    ),
                                                )
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = zoom
                                    scaleY = zoom
                                    translationX = pan.x
                                    translationY = pan.y
                                },
                        onError = { error = "图片读取失败" },
                    )
                else {
                    AndroidView(
                        factory = {
                            PlayerView(it).apply {
                                this.player = player
                                useController = true
                                controllerShowTimeoutMs = 3000
                                setShowPreviousButton(false); setShowNextButton(false)
                                // Enables both normal and compact Media3 fullscreen controls.
                                if (video) setFullscreenButtonClickListener { fullscreen = !fullscreen }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { view ->
                            view.keepScreenOn = playing
                            if (video) {
                                // Media3 1.4.1 has no public fullscreen-state setter. Reflect our
                                // state after toolbar entry or Back exits as well as player clicks.
                                listOf(androidx.media3.ui.R.id.exo_fullscreen, androidx.media3.ui.R.id.exo_minimal_fullscreen).forEach { id ->
                                    view.findViewById<ImageView>(id)?.apply {
                                        setImageResource(if (fullscreen) androidx.media3.ui.R.drawable.exo_icon_fullscreen_exit else androidx.media3.ui.R.drawable.exo_icon_fullscreen_enter)
                                        contentDescription = if (fullscreen) "退出全屏" else "全屏播放"
                                    }
                                }
                            }
                            ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
                                val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
                                view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_bottom_bar)
                                    ?.setPadding(if (fullscreen) cutout.left else 0, 0, if (fullscreen) cutout.right else 0, 0)
                                insets
                            }
                            ViewCompat.requestApplyInsets(view)
                        },
                    )
                }
                error?.let {
                    Surface(
                        Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(
                            it,
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

private tailrec fun Context.mediaActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.mediaActivity()
    else -> null
}

@Composable
private fun MediaFullscreenWindow(fullscreen: Boolean) {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(window, fullscreen) {
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        val previousCutout = if (Build.VERSION.SDK_INT >= 28) window?.attributes?.layoutInDisplayCutoutMode else null
        if (fullscreen && window != null) {
            if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (fullscreen && window != null) {
                controller?.show(WindowInsetsCompat.Type.systemBars())
                if (previousBehavior != null) controller?.systemBarsBehavior = previousBehavior
                if (Build.VERSION.SDK_INT >= 28 && previousCutout != null) window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = previousCutout
                }
            }
        }
    }
}
