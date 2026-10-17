package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.noties.markwon.Markwon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class ChatStreamLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun multiLineStreamChunkNeverPushesTheExecutionCardDownBeforeFollowing() = assertBottomAnchored(false)
    @Test fun nativeMessageGrowthKeepsTheExecutionCardAnchored() = assertBottomAnchored(true)

    private fun assertBottomAnchored(nativeText: Boolean) {
        val body = mutableStateOf((1..35).joinToString("\n") { "Streaming line $it with more text" })
        val bottoms = mutableListOf<Float>()
        val heights = mutableListOf<Int>()
        compose.setContent {
            MaterialTheme {
                val scroll = rememberLazyListState()
                val rows = chatTimelineRows(chatMessageItems(listOf(HermesMessage("assistant", body.value, 1))), true, false)
                rememberChatFollowing(scroll, "session", false, true, 0L, 0L, "execution")
                ChatTimelineList(rows, scroll, Modifier.size(360.dp, 336.dp)) { row ->
                    when (row.kind) {
                    ChatRowKind.Message -> {
                        Surface { Column(Modifier.padding(14.dp).onGloballyPositioned { heights += it.size.height }) {
                            val text = row.message!!.message.text
                            if (nativeText) {
                                val context = LocalContext.current
                                val markwon = remember(context) { Markwon.create(context) }
                                AndroidView(factory = { MessageTextView(it).apply {
                                    textSize = 15f; includeFontPadding = false; setPadding(0, 0, 0, 0)
                                    setTextIsSelectable(true)
                                } }, update = {
                                    it.setFooter("22:27", android.graphics.Color.GRAY)
                                    it.renderMessage(text, false, markwon)
                                })
                            }
                            else Text(text, fontSize = 15.sp, lineHeight = 20.sp)
                        } }
                    }
                    ChatRowKind.Execution -> {
                        Box(Modifier.fillMaxWidth().height(108.dp).onGloballyPositioned {
                            bottoms += it.positionInRoot().y + it.size.height
                        })
                    }
                    ChatRowKind.Bottom -> Spacer(Modifier.height(1.dp))
                    else -> Unit
                    }
                }
            }
        }
        compose.waitForIdle()
        val settled = bottoms.last()
        compose.runOnIdle { bottoms.clear() }
        repeat(3) {
            val initialHeight = heights.last()
            compose.runOnIdle { body.value += "\nChunk line one\nChunk line two\nChunk line three\nChunk line four" }
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeBy(1_000)
            compose.waitUntil(timeoutMillis = 5_000) { bottoms.isNotEmpty() && heights.last() > initialHeight }
            compose.waitForIdle()
        }
        assertTrue("No intermediate frame may push the execution card down: settled=$settled, observed=$bottoms",
            bottoms.isNotEmpty() && bottoms.all { abs(it - settled) <= 1f })
    }

    @Test fun readingHistoryStaysAnchoredAndExplicitLatestReturnsToBottom() {
        val messages = mutableStateOf((1L..30L).map { HermesMessage("assistant", "Message $it", it) })
        val latest = mutableLongStateOf(0L)
        lateinit var state: LazyListState
        lateinit var following: MutableState<Boolean>
        lateinit var scope: CoroutineScope
        compose.setContent {
            val scroll = rememberLazyListState()
            val rows = chatTimelineRows(chatMessageItems(messages.value), false, false)
            val follow = rememberChatFollowing(scroll, "session", false, true, 0L, latest.longValue, rows[1].key)
            val uiScope = rememberCoroutineScope()
            SideEffect { state = scroll; following = follow; scope = uiScope }
            ChatTimelineList(rows, scroll, Modifier.size(360.dp, 336.dp)) { row ->
                if (row.kind == ChatRowKind.Message) Box(Modifier.fillMaxWidth().height(80.dp))
                else if (row.kind == ChatRowKind.Bottom) Spacer(Modifier.height(1.dp))
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { following.value = false; scope.launch { state.scrollToItem(6, 17) } }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        fun anchor() = state.layoutInfo.visibleItemsInfo.first { it.index == state.firstVisibleItemIndex }.key to state.firstVisibleItemScrollOffset
        val before = anchor()
        compose.runOnIdle { messages.value = listOf(HermesMessage("assistant", "Older", 99)) + messages.value + HermesMessage("assistant", "Newest", 100) }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals(before, anchor())
        compose.runOnIdle { latest.longValue++ }
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }

    @Test fun shortConversationsStillStartAtTheTopInChronologicalOrder() {
        val positions = mutableMapOf<Long, Float>()
        compose.setContent {
            val scroll = rememberLazyListState()
            val rows = chatTimelineRows(chatMessageItems(listOf(HermesMessage("user", "Question", 1),
                HermesMessage("assistant", "Answer", 2))), false, false)
            ChatTimelineList(rows, scroll, Modifier.size(360.dp, 336.dp)) { row ->
                if (row.kind == ChatRowKind.Message) Box(Modifier.fillMaxWidth().height(40.dp)
                    .onGloballyPositioned { positions[row.message!!.message.serverId] = it.positionInRoot().y })
                else if (row.kind == ChatRowKind.Bottom) Spacer(Modifier.height(1.dp))
            }
        }
        compose.waitForIdle()
        assertEquals(24f, positions.getValue(1), 1f)
        assertTrue(positions.getValue(2) > positions.getValue(1))
    }
}
