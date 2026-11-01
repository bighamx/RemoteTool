package com.chuckiehelper.mobile.nativeui

import android.text.Spanned
import android.text.style.URLSpan
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MessageLinkifyTest {
    @Test fun numericAgentOutputAndBareMailStayPlainWhileExplicitLinksWork() {
        val context = RuntimeEnvironment.getApplication()
        val view = TextView(context)
        val source = "Call +1 650-253-0000\nhttps://example.com/docs\nhelp@example.com\n[邮件](mailto:help@example.com)\n" +
            (1..300).joinToString("\n") { "request_id=17927626341234567890; tokens=12345678901234567890" }
        createMessageMarkwon(context).setMarkdown(view, source)
        val text = view.text as Spanned
        val links = text.getSpans(0, text.length, URLSpan::class.java).map { it.url }
        assertFalse(links.any { it.startsWith("tel:") })
        assertTrue(links.any { it.startsWith("https://example.com") })
        val plainMail=text.toString().indexOf("help@example.com")
        assertEquals(0,text.getSpans(plainMail,plainMail+"help@example.com".length,android.text.style.ClickableSpan::class.java).size)
        assertTrue(links.contains("mailto:help@example.com")) // Explicit Markdown mail link remains actionable.
        assertEquals(2,text.getSpans(0,text.length,android.text.style.ClickableSpan::class.java).size)
        assertTrue(text.toString().contains("17927626341234567890"))
    }
}
