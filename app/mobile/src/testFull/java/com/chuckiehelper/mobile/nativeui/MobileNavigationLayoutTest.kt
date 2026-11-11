package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
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
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("navigation-test", 0)
        prefs.edit().clear().commit()
        val settings = MobileNavigationSettings(prefs)
        var editing by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (editing) MobileNavigationEditor(settings) { editing = false } } }
        compose.onNodeWithContentDescription("上移Claude").performClick()
        compose.onNodeWithText("保存").performClick()
        compose.runOnIdle {
            assertEquals("Claude Code", MobileNavigationSettings(prefs).order[2])
            editing = true
        }
        compose.onNodeWithContentDescription("下移Claude").performClick()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals("Claude Code", MobileNavigationSettings(prefs).order[2]); prefs.edit().clear().commit() }
    }
    @Test fun editionKeepsFilesAsARealTabAndExposesTheNavigationEditorInMore() {
        var edition: AppEdition? = null
        var clicked: String? = null
        compose.setContent { MaterialTheme { Column { val current = rememberAppEdition(); SideEffect { edition = current }; current.MoreItems { clicked = it } } } }
        compose.onNodeWithText("自定义底栏").assertIsDisplayed().performClick()
        compose.onNodeWithText("保存").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("文件").performClick()
        compose.runOnIdle {
            assertEquals("文件", clicked)
            assertEquals("文件", edition!!.normalizeRoute("文件"))
            assertTrue(edition!!.isNavigationRoute("文件"))
            assertTrue(edition!!.isNavigationRoute("Claude Code"))
        }
    }
}
