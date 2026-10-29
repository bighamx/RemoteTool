package com.chuckiehelper.mobile.nativeui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w360dp-h800dp-mdpi")
class ProviderModelFieldTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun typingFiltersWithoutLosingInputAndClearRestoresChoices() {
        val value = mutableStateOf("old-model")
        compose.setContent { MaterialTheme {
            Box(Modifier.padding(top = 300.dp)) {
                ProviderModelField(value.value, listOf("deepseek", "gpt-test"), true) { value.value = it }
            }
        } }
        compose.onNodeWithText("old-model").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("deep")
        compose.onNodeWithText("deepseek").assertExists()
        compose.onNodeWithText("gpt-test").assertDoesNotExist()
        compose.onNodeWithContentDescription("清空模型").performClick()
        compose.runOnIdle { assertEquals("", value.value) }
        compose.onNodeWithText("gpt-test").assertExists()
        compose.onNodeWithText("deepseek").performClick()
        compose.runOnIdle { assertEquals("deepseek", value.value) }
    }

    @Test fun popupIsAboveTheInputWithinWindowBounds() {
        val anchor = IntRect(20, 300, 340, 360)
        val position = AboveModelFieldPosition.calculatePosition(anchor, IntSize(360,800), LayoutDirection.Ltr, IntSize(320,240))
        assertEquals(20, position.x)
        assertEquals(anchor.top, position.y + 240)
    }
}
