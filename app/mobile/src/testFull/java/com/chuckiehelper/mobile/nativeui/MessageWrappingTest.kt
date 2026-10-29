package com.chuckiehelper.mobile.nativeui

import android.text.Layout
import android.text.Spannable
import android.text.Selection
import android.view.View
import android.view.ViewGroup
import io.noties.markwon.Markwon
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MessageWrappingTest {
    @Test fun longMachineOutputWrapsWithoutChangingContentOrSelection() {
        val context = RuntimeEnvironment.getApplication()
        val view = MessageTextView(context).apply {
            layoutParams = ViewGroup.LayoutParams(280, ViewGroup.LayoutParams.WRAP_CONTENT)
            setTextIsSelectable(true)
        }
        val source = "https://example.com/" + "segment1234567890/".repeat(400) +
            "\n\nC:\\Users\\" + "directory0123456789\\".repeat(400) + "file.log" +
            "\n\n" + "12345678901234567890".repeat(400)
        // Exercise Android layout independently of URL detection/Markdown parsing.
        view.renderMessage(source, false, Markwon.create(context))
        assertEquals(Layout.BREAK_STRATEGY_SIMPLE, view.breakStrategy)
        assertEquals(Layout.HYPHENATION_FREQUENCY_NONE, view.hyphenationFrequency)
        view.measure(View.MeasureSpec.makeMeasureSpec(280, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        view.onPreDraw()
        assertEquals(source, view.text.toString())
        assertTrue(view.isTextSelectable)
        Selection.setSelection(view.text as Spannable, 0, 19)
        assertEquals("https://example.com", view.text.subSequence(view.selectionStart, view.selectionEnd).toString())
        assertTrue(view.layout.lineCount > 3)
    }
}
