package com.chuckiehelper.mobile.nativeui

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.compose.BackHandler
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PredictiveBackSceneTest {
    @get:Rule val compose = createComposeRule()

    @Test fun previewThenCancelPreservesPageAndDraftAndReleaseCommitsOnce() {
        lateinit var dispatcher: OnBackPressedDispatcher
        var page by mutableStateOf("B")
        var commits = 0
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            MaterialTheme {
                PredictiveBackScene(page, "A".takeIf { page == "B" }, { commits++; page = "A" }, enabled = true) { screen ->
                    if (screen == "A") Text("Previous page") else {
                        var draft by remember { mutableStateOf("my draft") }
                        SceneBackHandler { error("Scene must own the page root callback") }
                        TextField(draft, { draft = it }, Modifier.testTag("draft"))
                    }
                }
            }
        }
        compose.onNodeWithTag("draft").performTextReplacement("edited draft")
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(.6f)) }
        compose.mainClock.advanceTimeBy(64)
        compose.onNodeWithText("Previous page").assertExists()
        assertTrue("Current page must move with gesture progress",
            compose.onNodeWithTag("draft").getUnclippedBoundsInRoot().left > 0.dp)
        compose.runOnIdle { assertEquals("B", page); assertEquals(0, commits) }
        var previousOffset = compose.onNodeWithTag("draft").getUnclippedBoundsInRoot().left
        compose.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        repeat(24) {
            compose.mainClock.advanceTimeByFrame()
            val offset = compose.onNodeWithTag("draft").getUnclippedBoundsInRoot().left
            assertTrue("Cancellation must return steadily, not jump forward: $previousOffset -> $offset",
                offset <= previousOffset + 1.dp)
            assertTrue("Cancellation must not overshoot the original page", offset >= (-1).dp)
            previousOffset = offset
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onNodeWithTag("draft").assertTextEquals("edited draft")
        compose.onNodeWithText("Previous page").assertDoesNotExist()
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(.7f)) }
        compose.waitForIdle()
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Previous page").assertIsDisplayed()
        compose.onNodeWithTag("draft").assertDoesNotExist()
        compose.runOnIdle { assertEquals("A", page); assertEquals(1, commits) }
    }

    @Test fun innerBackActionTakesPriorityWithoutNavigating() {
        lateinit var dispatcher: OnBackPressedDispatcher
        var nested by mutableStateOf(true)
        var commits = 0
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            MaterialTheme {
                PredictiveBackScene("B", "A", { commits++ }, enabled = true) {
                    BackHandler(enabled = nested) { nested = false }
                    Text("page")
                }
            }
        }
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.runOnIdle { assertFalse(nested); assertEquals(0, commits) }
    }

    @Test fun defaultOffDoesNotPreviewAndStillReturnsNormally() {
        lateinit var dispatcher: OnBackPressedDispatcher
        var page by mutableStateOf("B")
        compose.setContent {
            dispatcher = LocalOnBackPressedDispatcherOwner.current!!.onBackPressedDispatcher
            MaterialTheme {
                PredictiveBackScene(page, "A".takeIf { page == "B" }, { page = "A" }) { Text(it) }
            }
        }
        compose.runOnUiThread { dispatcher.dispatchOnBackStarted(event(0f)) }
        compose.runOnUiThread { dispatcher.dispatchOnBackProgressed(event(.6f)) }
        compose.onNodeWithText("A").assertDoesNotExist()
        compose.onNodeWithText("B").assertIsDisplayed()
        compose.runOnUiThread { dispatcher.onBackPressed() }
        compose.onNodeWithText("A").assertIsDisplayed()
    }

    private fun event(progress: Float) = BackEventCompat(0f, 100f, progress, BackEventCompat.EDGE_LEFT)
}
