@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
fun MediaViewer(api: NativeApi, file: JSONObject, close: () -> Unit, download: () -> Unit) {
    val context = LocalContext.current
    val path = file.optString("path")
    val image =
        file.optString("name").substringAfterLast('.').lowercase() in
            listOf("jpg", "jpeg", "png", "webp", "bmp", "gif")
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var error by remember { mutableStateOf<String?>(null) }
    val imageLoader =
        remember(context) { ImageLoader.Builder(context).okHttpClient(NativeApi.client).build() }
    Dialog(
        onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(file.optString("name"), maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = close) { Icon(Icons.Outlined.Close, "关闭") }
                    },
                    actions = {
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
                                .data(api.base + "/api/files/preview-image?path=${q(path)}")
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
                    val player =
                        remember(api, path) {
                            ExoPlayer.Builder(context)
                                .setMediaSourceFactory(
                                    DefaultMediaSourceFactory(
                                        OkHttpDataSource.Factory(NativeApi.client)
                                            .setDefaultRequestProperties(
                                                mapOf("Cookie" to api.cookie())
                                            )
                                    )
                                )
                                .build()
                                .apply {
                                    setMediaItem(
                                        MediaItem.fromUri(
                                            api.base + "/api/files/stream?path=${q(path)}"
                                        )
                                    )
                                    addListener(
                                        object : Player.Listener {
                                            override fun onPlayerError(e: PlaybackException) {
                                                error = "手机无法解码此格式，可下载后使用兼容的播放器打开。\n不进行服务端转码。"
                                            }
                                        }
                                    )
                                    prepare()
                                    playWhenReady = true
                                }
                        }
                    DisposableEffect(player) { onDispose { player.release() } }
                    AndroidView(
                        factory = {
                            PlayerView(it).apply {
                                this.player = player
                                useController = true
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
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
