package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class MobileNavigationLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sixTabsAreVisibleAndClaudeClickSelectsTheSharedWorkspaceRoute() {
        var selected = "系统"
        compose.setContent { MaterialTheme { Surface(Modifier.width(360.dp)) {
            var route by remember { mutableStateOf(selected) }
            FullMobileNavigation(normalizeMobileNavigation(null), route) { route = it; selected = it }
        } } }
        listOf("系统", "远程", "Codex", "Claude", "Hermes", "更多").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Claude").performClick()
        compose.runOnIdle { assertEquals("Claude Code", selected) }
        compose.onNodeWithText("Claude").assertIsSelected()
    }
    @Test fun editorSavesAcrossRestartsAndCancelLeavesSavedOrderAlone() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("navigation-drag-test", 0)
        prefs.edit().clear().commit()
        val settings = MobileNavigationSettings(prefs)
        var editing by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (editing) MobileNavigationEditor(settings) { editing = false } } }
        compose.onNodeWithTag("navigation-order").performScrollToNode(hasTestTag("navigation-row-Claude Code"))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("navigation-row-Claude Code").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("navigation-row-Claude Code").performTouchInput {
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, -18f), delayMillis = 16) }; up()
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("保存布局").performClick()
        compose.runOnIdle {
            assertEquals("Claude Code", MobileNavigationSettings(prefs).order[2])
            editing = true
        }
        compose.onNodeWithTag("navigation-order").performScrollToNode(hasTestTag("navigation-row-Claude Code"))
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("navigation-row-Claude Code").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("navigation-row-Claude Code").performTouchInput {
            repeat(6) { moveBy(androidx.compose.ui.geometry.Offset(0f, 18f), delayMillis = 16) }; up()
        }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("Claude Code", MobileNavigationSettings(prefs).order[2]); prefs.edit().clear().commit() }
    }
    @Test fun draggingToTheBottomEdgeScrollsAcrossTheFiveItemBoundary() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("navigation-auto-scroll-test", 0)
        prefs.edit().clear().commit()
        val settings = MobileNavigationSettings(prefs)
        var editing by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (editing) MobileNavigationEditor(settings) { editing = false } } }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("navigation-row-系统").performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(650)
        compose.onNodeWithTag("navigation-order").performTouchInput { moveTo(androidx.compose.ui.geometry.Offset(center.x, height - 8f), delayMillis = 32) }
        compose.mainClock.advanceTimeBy(1800)
        compose.onNodeWithTag("navigation-order").performTouchInput { up() }
        compose.mainClock.autoAdvance = true
        compose.onNodeWithText("保存布局").performClick()
        compose.runOnIdle {
            assertTrue("The held row should move from the bottom bar into More", settings.order.indexOf("系统") >= 5)
            assertEquals(mobileDestinations.size, settings.order.distinct().size)
            prefs.edit().clear().commit()
        }
    }
    @Test fun editionKeepsFilesAsARealTabAndOpensTheEditorFromTheToolbar() {
        var edition: AppEdition? = null
        var clicked: String? = null
        compose.setContent { MaterialTheme { Column { val current = rememberAppEdition(); SideEffect { edition = current }; current.ToolbarActions("更多") { clicked = it }; current.MoreItems { clicked = it } } } }
        compose.onNodeWithText("自定义底栏").assertDoesNotExist()
        compose.onNodeWithContentDescription("自定义底栏").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals("自定义底栏", clicked) }
        compose.onNodeWithText("文件").performClick()
        compose.runOnIdle {
            assertEquals("文件", clicked)
            assertEquals("文件", edition!!.normalizeRoute("文件"))
            assertTrue(edition!!.isNavigationRoute("文件"))
            assertTrue(edition!!.isNavigationRoute("Claude Code"))
        }
    }
}
