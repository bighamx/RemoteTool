package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import java.util.UUID
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
class CodexWarmupUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun explanationAppearsOnlyAfterTappingTheInformationIcon() {
        val app = RuntimeEnvironment.getApplication()
        val device = "warmup-ui-${UUID.randomUUID()}"
        val model = HermesModel(app, device, "codex")
        val models = ViewModelStore().apply { put("warmup", model) }
        @Suppress("UNCHECKED_CAST")
        val state = HermesModel::class.java.getDeclaredField("workspaceWarmup\$delegate").apply { isAccessible = true }.get(model) as MutableState<org.json.JSONObject?>
        state.value = obj("supported" to true, "enabled" to false)
        val explanation = "每 10 分钟检查此服务端保存的全部官方账号和工作空间，符合条件时发送最小对话，开启下一轮 5 小时额度窗口。"
        try {
            compose.setContent { MaterialTheme { Surface(Modifier.width(360.dp)) { CodexWarmupSettings(model) } } }
            compose.onNodeWithText("自动热身").assertIsDisplayed()
            compose.onNodeWithText(explanation).assertDoesNotExist()
            compose.onNodeWithContentDescription("自动热身说明").performClick()
            compose.onNodeWithText(explanation).assertIsDisplayed()
        } finally {
            models.clear()
            app.getSharedPreferences("codex-$device", 0).edit().clear().commit()
        }
    }
}
