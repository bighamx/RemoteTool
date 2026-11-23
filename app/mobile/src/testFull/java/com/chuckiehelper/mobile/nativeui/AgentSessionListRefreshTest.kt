package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
@Config(sdk = [28], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class AgentSessionListRefreshTest {
    @get:Rule val compose = createComposeRule()

    private fun pull() {
        compose.onNodeWithTag("sessions").performTouchInput {
            swipe(Offset(center.x, 30f), Offset(center.x, 370f), 600)
        }
        compose.waitForIdle()
    }

    @Test fun middleOfListScrollsNormallyAndTopRefreshesOnlyOnceWhileBusy() {
        val list = LazyListState()
        var refreshing by mutableStateOf(false)
        var requests = 0
        compose.setContent { MaterialTheme { Surface(Modifier.width(360.dp).height(400.dp)) {
            AgentSessionListRefresh(list, refreshing, { requests++; refreshing = true }, Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize().testTag("sessions"), state = list) {
                    items(40) { Text("会话 $it", Modifier.fillMaxWidth().height(64.dp)) }
                }
            }
        } } }
        compose.runOnIdle { list.requestScrollToItem(15) }
        compose.waitForIdle()
        pull()
        compose.runOnIdle {
            assertEquals(0, requests)
            assertTrue(list.canScrollBackward)
            list.requestScrollToItem(0)
        }
        compose.waitForIdle()
        pull()
        compose.runOnIdle { assertEquals(1, requests); assertTrue(refreshing) }
        pull()
        compose.runOnIdle { assertEquals(1, requests) }
    }

    @Test fun emptyListCanRefreshButDisabledRefreshDoesNotIntercept() {
        val list = LazyListState()
        var enabled by mutableStateOf(false)
        var requests = 0
        compose.setContent { MaterialTheme { Surface(Modifier.width(360.dp).height(400.dp)) {
            AgentSessionListRefresh(list, false, { requests++ }, Modifier.fillMaxSize(), enabled = enabled) {
                LazyColumn(Modifier.fillMaxSize().testTag("sessions"), state = list) {}
            }
        } } }
        pull()
        compose.runOnIdle { assertEquals(0, requests); enabled = true }
        compose.waitForIdle()
        pull()
        compose.runOnIdle { assertEquals(1, requests) }
    }
}
