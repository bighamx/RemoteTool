package com.chuckiehelper.mobile.nativeui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.icu.text.BreakIterator
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer

/** Only the administration file path uses character-based layout. The path itself is never changed. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun AdminFilePath(path: String) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val lines = remember(path, width, style, measurer, density.density, density.fontScale) {
            val result = mutableListOf<TextLayoutResult>()
            val boundaries = BreakIterator.getCharacterInstance().apply { setText(path) }
            var start = boundaries.first()
            var end = start
            var next = boundaries.next()
            var fitting: TextLayoutResult? = null
            while (next != BreakIterator.DONE) {
                var candidate = measurer.measure(path.substring(start, next), style = style, softWrap = false)
                if (end > start && candidate.size.width > width) {
                    result.add(requireNotNull(fitting))
                    start = end
                    candidate = measurer.measure(path.substring(start, next), style = style, softWrap = false)
                }
                fitting = candidate
                end = next
                next = boundaries.next()
            }
            fitting?.let(result::add)
            result
        }
        val height = with(density) { lines.sumOf { it.size.height }.toDp() }
        Canvas(Modifier.fillMaxWidth().height(height).combinedClickable(
            onClick = {}, onLongClickLabel = "复制路径", onLongClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("文件路径", path))
                Toast.makeText(context, "已复制路径", Toast.LENGTH_SHORT).show()
            },
        ).semantics { text = AnnotatedString(path) }) {
            var top = 0f
            lines.forEach { line ->
                drawText(line, topLeft = Offset(0f, top))
                top += line.size.height
            }
        }
    }
}
