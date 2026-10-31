package com.chuckiehelper.mobile.nativeui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
class MarkdownDocumentTest {
    @get:Rule val compose = createComposeRule()
    @Test fun markdownOpensInPreviewAndKeepsEditsWhenSwitchingModes() {
        var saved:String? = null
        compose.setContent {
            MaterialTheme { TextDocument("README.md", "# Original", true, {}, preview = { Text("Rendered: $it") }) { saved=it } }
        }
        compose.onNodeWithText("Rendered: # Original").assertIsDisplayed()
        compose.onNodeWithText("编辑").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("# Updated")
        compose.onNodeWithText("预览").performClick()
        compose.onNodeWithText("Rendered: # Updated").assertIsDisplayed()
        compose.onNodeWithText("保存").performClick()
        assertEquals("# Updated",saved)
        assertTrue(isMarkdownDocument("D:/docs/README.MD"))
        assertFalse(isMarkdownDocument("D:/md/notes.txt"))
    }
}
