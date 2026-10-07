package com.chuckiehelper.mobile.nativeui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.linkify.LinkifyPlugin

@Composable
fun HermesMarkdown(text: String, modifier: Modifier = Modifier, footer: String = "") {
    val context = LocalContext.current
    val markwon =
        remember(context) {
            Markwon.builder(context)
                .usePlugin(TablePlugin.create(context))
                .usePlugin(StrikethroughPlugin.create())
                .usePlugin(LinkifyPlugin.create())
                .build()
        }
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val link = MaterialTheme.colorScheme.primary.toArgb()
    val footerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f).toArgb()
    AndroidView(
        modifier = modifier,
        factory = {
            MessageTextView(it).apply {
                textSize = 15f
                includeFontPadding = false
                setTextIsSelectable(true)
                setPadding(0, 0, 0, 0)
            }
        },
        update = { view ->
            view.setFooter(footer, footerColor)
            view.setTextColor(color)
            view.setLinkTextColor(link)
            if (view.tag != text) {
                markwon.setMarkdown(view, text)
                view.tag = text
            }
        },
    )
}
