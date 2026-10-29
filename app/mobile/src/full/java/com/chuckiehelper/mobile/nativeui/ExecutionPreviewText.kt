package com.chuckiehelper.mobile.nativeui

import android.icu.text.BreakIterator
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.*

/** Character wrapping for the bounded tool preview, without modifying command text. */
@Composable
internal fun ExecutionPreviewText(content: String, style: TextStyle) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth.coerceAtLeast(1)
        val lines = remember(content, width, style, density.density, density.fontScale, measurer) {
            buildList<TextLayoutResult> {
                content.split('\n').forEach { paragraph ->
                    if (paragraph.isEmpty()) add(measurer.measure(" ", style, softWrap = false))
                    else {
                        val iterator = BreakIterator.getCharacterInstance().apply { setText(paragraph) }
                        val ends = buildList { var end = iterator.first(); while (end != BreakIterator.DONE) { add(end); end = iterator.next() } }
                        var start = 0
                        while (start < ends.lastIndex) {
                            var low = start + 1
                            var high = ends.lastIndex
                            var best = low
                            while (low <= high) {
                                val middle = (low + high) / 2
                                val measured = measurer.measure(paragraph.substring(ends[start], ends[middle]), style, softWrap = false)
                                if (measured.size.width <= width) { best = middle; low = middle + 1 }
                                else high = middle - 1
                            }
                            add(measurer.measure(paragraph.substring(ends[start], ends[best]), style, softWrap = false))
                            start = best
                        }
                    }
                }
            }
        }
        Canvas(Modifier.fillMaxWidth().height(with(density) { lines.sumOf { it.size.height }.toDp() })
            .semantics { text = AnnotatedString(content) }) {
            var top = 0f
            lines.forEach { drawText(it, topLeft = Offset(0f, top)); top += it.size.height }
        }
    }
}
