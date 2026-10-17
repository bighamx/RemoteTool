package com.chuckiehelper.mobile.nativeui

import android.app.Activity
import android.graphics.Rect
import android.text.Selection
import android.text.Spannable
import android.text.method.ArrowKeyMovementMethod
import android.view.View
import android.widget.FrameLayout
import io.noties.markwon.Markwon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MessageTextViewScrollTest {
    @Test fun passiveAppendDoesNotAskTheParentToRevealTheOldCursor() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            var requests = 0
            val parent = object : FrameLayout(activity) {
                override fun requestChildRectangleOnScreen(child: View, rectangle: Rect, immediate: Boolean): Boolean {
                    requests++
                    return true
                }
            }
            val view = MessageTextView(activity).apply {
                setTextIsSelectable(true)
                movementMethod = ArrowKeyMovementMethod.getInstance()
                setPadding(0, 0, 0, 0)
            }
            parent.addView(view, FrameLayout.LayoutParams(300, 200))
            activity.setContentView(parent)
            val markwon = Markwon.create(activity)
            val source = (1..40).joinToString("\n") { "Line $it - streaming text" }
            view.renderMessage(source, false, markwon)
            view.requestFocus()
            Selection.setSelection(view.text as Spannable, 0)
            parent.measure(exact(300), exact(200))
            parent.layout(0, 0, 300, 200)
            requests = 0
            repeat(5) { index ->
                view.renderMessage(source + "\nMore text " + "x".repeat(index + 1), false, markwon)
                parent.measure(exact(300), exact(200))
                parent.layout(0, 0, 300, 200)
                view.onPreDraw()
            }
            assertEquals("Passive stream changes must not move the parent to the old selection", 0, requests)
            view.requestRectangleOnScreen(Rect(0, 0, 20, 20), true)
            assertTrue("Explicit accessibility/visibility requests must still reach the parent", requests > 0)
            requests = 0
            Selection.setSelection(view.text as Spannable, 0, 4)
            view.onPreDraw()
            assertTrue("Deliberate text selection must still be able to reveal its handles", requests > 0)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun repeatedMeasuresDoNotAccumulateFooterHeightDuringStreaming() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val view = MessageTextView(controller.get()).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT)
                setTextIsSelectable(true)
                setPadding(0, 0, 0, 0)
                setFooter("12:34", android.graphics.Color.GRAY)
            }
            val markwon = Markwon.create(controller.get())
            var source = "A streaming message with several lines of text. ".repeat(5)
            repeat(30) {
                source += " more"
                view.renderMessage(source, false, markwon)
                val heights = (1..4).map {
                    view.forceLayout()
                    view.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.AT_MOST),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                    view.measuredHeight
                }
                assertEquals("Repeated measurement must not change the same text's height", 1, heights.distinct().size)
            }
        } finally { controller.pause().stop().destroy() }
    }

    private fun exact(size: Int) = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)
}
