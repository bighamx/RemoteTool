package com.chuckiehelper.mobile.nativeui

import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import io.noties.markwon.core.spans.CodeBlockSpan
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.core.spans.StrongEmphasisSpan
import io.noties.markwon.ext.tables.TableRowSpan
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class LiveMarkdownTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun streamingMarkdownReflowsWithoutMovingTheBottomOrReplacingTheView() {
        val source = mutableStateOf("## Live heading\n\n" + (1..35).joinToString("\n\n") { "Streaming body line $it" })
        val streaming = mutableStateOf(true)
        val bottoms = mutableListOf<Float>()
        compose.setContent {
            MaterialTheme {
                val scroll = rememberLazyListState()
                val rows = chatTimelineRows(chatMessageItems(listOf(HermesMessage("assistant", source.value, 1))), true, false)
                rememberChatFollowing(scroll, "session", false, true, 0L, 0L, "execution")
                ChatTimelineList(rows, scroll, Modifier.size(360.dp, 336.dp)) { row ->
                    when (row.kind) {
                        ChatRowKind.Message -> Surface { Column(Modifier.padding(14.dp)) {
                            HermesMarkdown(row.message!!.message.text, footer = "22:27", streaming = streaming.value)
                        } }
                        ChatRowKind.Execution -> Box(Modifier.fillMaxWidth().height(108.dp).onGloballyPositioned {
                            bottoms += it.positionInRoot().y + it.size.height
                        })
                        ChatRowKind.Bottom -> Spacer(Modifier.height(1.dp))
                        else -> Unit
                    }
                }
            }
        }
        compose.waitForIdle()
        val view = requireNotNull(findMessageView(compose.activity.window.decorView))
        val settled = bottoms.last()
        assertTrue(hasSpan(view, HeadingSpan::class.java))
        assertTrue(view.isTextSelectable)
        val expectedRenderer = createMessageMarkwon(compose.activity)

        fun append(chunk: String) {
            compose.runOnIdle { source.value += chunk }
            Snapshot.sendApplyNotifications()
            val expected = expectedRenderer.toMarkdown(source.value).toString()
            compose.waitUntil(timeoutMillis = 5_000) {
                ShadowLooper.idleMainLooper(60, TimeUnit.MILLISECONDS)
                Snapshot.sendApplyNotifications()
                view.text.toString() == expected
            }
            compose.waitForIdle()
            assertTrue("Formatting must update while the stream is still running", streaming.value)
            assertSame(view, findMessageView(compose.activity.window.decorView))
            assertTrue("Markdown reflow must not expose a displaced bottom: $bottoms",
                bottoms.all { abs(it - settled) <= 1f })
        }

        append("\n\n**Bold ")
        append("text** and [a link](https://example.com)")
        assertTrue(hasSpan(view, StrongEmphasisSpan::class.java))
        assertTrue(hasSpan(view, ClickableSpan::class.java))
        append("\n\n```kotlin\nval answer = 42")
        assertTrue(hasSpan(view, CodeBlockSpan::class.java))
        append("\n```\n\n| Name | Value |")
        append("\n| --- | --- |\n| alpha")
        append(" | 1 |\n| beta | 2 |")
        assertTrue(hasSpan(view, TableRowSpan::class.java))
        val formatted = view.text
        compose.runOnIdle { streaming.value = false }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertSame("Finishing must not replace or re-render the already formatted buffer", formatted, view.text)
        assertSame(view, findMessageView(compose.activity.window.decorView))
        assertTrue(bottoms.all { abs(it - settled) <= 1f })
    }

    private fun hasSpan(view: MessageTextView, type: Class<*>): Boolean {
        val text = view.text as Spanned
        return text.getSpans(0, text.length, type).isNotEmpty()
    }

    private fun findMessageView(view: View): MessageTextView? {
        if (view is MessageTextView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findMessageView(view.getChildAt(i))?.let { return it }
        }
        return null
    }
}
