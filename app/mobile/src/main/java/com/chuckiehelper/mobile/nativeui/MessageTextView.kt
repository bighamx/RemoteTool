package com.chuckiehelper.mobile.nativeui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.TextView
import android.util.TypedValue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

internal fun messageFooterFits(lastLineRight: Float, width: Int, footerWidth: Float, gap: Float): Boolean =
    lastLineRight + footerWidth + gap <= width + 0.5f

/** Draw metadata outside selectable Markdown; reserve its space using real line metrics. */
internal class MessageTextView(context: Context) : TextView(context) {
    private val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var footer = ""
    private var footerBaseline = 0f
    private var footerWidth = 0f
    private val gap get() = 8f * resources.displayMetrics.density

    fun setFooter(value: String, color: Int) {
        val size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        if (footer != value || footerPaint.color != color || footerPaint.textSize != size) {
            footer = value
            footerPaint.color = color
            footerPaint.textSize = size
            footerWidth = footerPaint.measureText(value)
            requestLayout()
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (footer.isEmpty()) return
        val maxWidth = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> Int.MAX_VALUE
            else -> MeasureSpec.getSize(widthMeasureSpec)
        }
        // A short bubble grows horizontally to include metadata, instead of adding a line.
        val textLayout = layout ?: return
        val last = textLayout.lineCount - 1
        if (last < 0) return
        val desiredWidth = max(measuredWidth, ceil(textLayout.getLineRight(last) + gap + footerWidth).toInt())
        val width = min(desiredWidth, maxWidth)
        if (width != measuredWidth)
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
        val finalLayout = layout ?: return
        val finalLine = finalLayout.lineCount - 1
        val metrics = footerPaint.fontMetrics
        if (messageFooterFits(finalLayout.getLineRight(finalLine), measuredWidth, footerWidth, gap)) {
            footerBaseline = finalLayout.getLineBaseline(finalLine).toFloat()
        } else {
            val top = max(measuredHeight, finalLayout.getLineBottom(finalLine))
            footerBaseline = top - metrics.ascent
            setMeasuredDimension(measuredWidth, top + ceil(metrics.descent - metrics.ascent).toInt())
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (footer.isNotEmpty()) canvas.drawText(footer, width - footerWidth, footerBaseline, footerPaint)
    }
}
