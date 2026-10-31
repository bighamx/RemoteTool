package com.chuckiehelper.mobile.nativeui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.json.JSONArray
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
class AgentApprovalUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun persistentRulesRequireConfirmationAndBusyDisablesApproval() {
        val request = obj("request_id" to "approval-1", "choices" to JSONArray(listOf(
            obj("id" to "once", "label" to "仅允许本次"),
            obj("id" to "session", "label" to "本会话允许"),
            obj("id" to "always", "label" to "总是允许此类命令", "detail" to "dotnet build"),
            obj("id" to "deny", "label" to "拒绝"))))
        val busy = mutableStateOf(false)
        val sent = mutableListOf<String>()
        compose.setContent { MaterialTheme { AgentApprovalButtons(request, busy.value) { sent += it } } }
        compose.onNodeWithText("总是允许此类命令").performClick()
        assertTrue(sent.isEmpty())
        compose.onNodeWithText("dotnet build", substring = true).assertIsDisplayed()
        compose.onNodeWithText("保存规则并允许").performClick()
        assertEquals(listOf("always"), sent)
        compose.runOnIdle { busy.value = true }
        compose.onNodeWithText("本会话允许").assertIsNotEnabled()
        compose.onNodeWithText("仅允许本次").assertIsNotEnabled()
        compose.onNodeWithText("拒绝").assertIsNotEnabled()
    }
}
