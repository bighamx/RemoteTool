package com.chuckiehelper.mobile.nativeui

import android.content.Context
import android.text.Spanned
import android.text.util.Linkify
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import android.text.method.ArrowKeyMovementMethod
import io.noties.markwon.Markwon
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.LinkResolverDef
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun createMessageMarkwon(context: Context, onLinkTap: ((String) -> Boolean)? = null): Markwon = Markwon.builder(context)
    .usePlugin(TablePlugin.create(context))
    .usePlugin(StrikethroughPlugin.create())
    // Agent output contains long numeric IDs/logs, not phone contacts. Android's
    // libphonenumber matcher can monopolize the UI thread and trigger an ANR.
    .usePlugin(LinkifyPlugin.create(Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES))
    .usePlugin(object : AbstractMarkwonPlugin() {
        override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
            val fallback = LinkResolverDef()
            builder.linkResolver { view, link ->
                if (onLinkTap?.invoke(link) != true) fallback.resolve(view, link)
            }
        }
    })
    .build()

private data class MarkdownFrame(val source: String, val parsed: Spanned)

@Composable
fun HermesMarkdown(text: String, modifier: Modifier = Modifier, footer: String = "", onBubbleTap: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    streaming: Boolean = false, onLinkTap: ((String) -> Boolean)? = null) {
    val context = LocalContext.current
    val currentLinkTap by rememberUpdatedState(onLinkTap)
    val markwon = remember(context) { createMessageMarkwon(context) { currentLinkTap?.invoke(it) == true } }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val footerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f).toArgb()
    // Keep the last styled frame visible while parsing the next snapshot off the
    // UI thread. One conflated collector prevents queued work or out-of-order results.
    var displayed by remember(markwon) { mutableStateOf(MarkdownFrame(text, markwon.toMarkdown(text))) }
    val latest by rememberUpdatedState(text to streaming)
    LaunchedEffect(markwon) {
        snapshotFlow { latest }.conflate().collect { target ->
            if (target.first != displayed.source) {
                displayed = withContext(Dispatchers.Default) {
                    MarkdownFrame(target.first, markwon.toMarkdown(target.first))
                }
            }
            if (target.second) delay(50)
        }
    }
    AndroidView(
        modifier = modifier.clipToBounds(),
        factory = {
            MessageTextView(it).apply {
                textSize = 15f
                includeFontPadding = false
                setTextIsSelectable(true)
                // MessageTextView owns short link taps; native selection keeps long presses.
                linksClickable = false
                setPadding(0, 0, 0, 0)
                movementMethod = ArrowKeyMovementMethod.getInstance()
            }
        },
        update = { view ->
            view.onBubbleTap = onBubbleTap
            view.setFooter(footer, footerColor)
            // 同值 setTextColor/setLinkTextColor 也会 invalidate 整个 TextView；
            // recomposition 风暴期（流式/切换会话）逐帧重绘就是闪烁感的一部分。
            if (view.currentTextColor != color) view.setTextColor(color)
            if (view.appliedLinkColor != link) { view.setLinkTextColor(link); view.appliedLinkColor = link }
            view.renderMarkdown(displayed.source, displayed.parsed, markwon)
        },
    )
}
