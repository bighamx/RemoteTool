package com.chuckiehelper.mobile.nativeui

import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.URLSpan
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MarkdownLinksTest {
    @Test fun filenamesAndCodeStayPlainWhileExplicitLinksWork() {
        val context=ApplicationProvider.getApplicationContext<android.content.Context>()
        val view=TextView(context)
        val markwon=createMessageMarkwon(context)
        markwon.setMarkdown(view,"另一个项目 agents.md / README.md / example.com / user@example.com")
        var text=view.text as Spanned
        assertEquals(0,text.getSpans(0,text.length,ClickableSpan::class.java).size)
        markwon.setMarkdown(view,"[规则](https://example.com/rules) https://example.com/docs `https://example.com/code`")
        text=view.text as Spanned
        assertEquals(2,text.getSpans(0,text.length,ClickableSpan::class.java).size)
        assertTrue(text.getSpans(0,text.length,URLSpan::class.java).any { it.url=="https://example.com/docs" })
    }
}
