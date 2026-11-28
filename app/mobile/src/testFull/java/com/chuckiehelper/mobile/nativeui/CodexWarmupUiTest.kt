package com.chuckiehelper.mobile.nativeui

import android.app.Application
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
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

    @Test
    fun accountsDialogShowsBothWorkspacesWithoutVerticalScrolling() {
        val app = RuntimeEnvironment.getApplication()
        val device = "workspace-layout-${UUID.randomUUID()}"
        val model = HermesModel(app, device, "codex")
        val models = ViewModelStore().apply { put("accounts", model) }
        val spaces = org.json.JSONArray((1..2).map { index ->
            obj("id" to "workspace-$index", "workspace_name" to "工作空间 $index", "name" to "工作空间 $index", "plan_type" to "plus")
        })
        val usage = obj("available" to true, "usage" to obj("rateLimits" to obj(
            "primary" to obj("usedPercent" to 3, "windowDurationMins" to 300, "resetsAt" to 1795768800),
            "secondary" to obj("usedPercent" to 20, "windowDurationMins" to 10080, "resetsAt" to 1795855200))))
        val values = mapOf(
            "workspaces" to obj("current" to "workspace-1", "data" to spaces),
            "accounts" to obj("current_identity" to obj("email" to "demo@example.invalid")),
            "workspaceUsages" to (1..2).associate { "workspace-$it" to usage },
            "workspaceWarmup" to obj("supported" to true, "enabled" to false, "last_check_at" to 1795750800,
                "items" to org.json.JSONArray((1..2).map { obj("account_id" to "workspace-$it", "message" to "已热身",
                    "last_warmup_at" to 1795750800, "next_reset_at" to 1795768800) }))
        )
        values.forEach { (name, value) ->
            @Suppress("UNCHECKED_CAST")
            val state = HermesModel::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }.get(model) as MutableState<Any?>
            state.value = value
        }
        try {
            compose.setContent { MaterialTheme { CodexAccountsDialog(model, {}, {}, {}) } }
            compose.onNodeWithText("工作空间与用量").assertIsDisplayed()
            compose.onNodeWithText("工作空间 1 · Plus").assertIsDisplayed()
            compose.onNodeWithText("工作空间 2 · Plus").assertIsDisplayed()
            compose.onAllNodesWithText("5 小时 · 剩余 97%").assertCountEquals(2)
            compose.onAllNodesWithText("周额度 · 剩余 80%").assertCountEquals(2)
            repeat(2) { index ->
                compose.onAllNodesWithText("5 小时 · 剩余 97%")[index].assertIsDisplayed()
                compose.onAllNodesWithText("周额度 · 剩余 80%")[index].assertIsDisplayed()
            }
            compose.onNodeWithText("三方 Provider").assertIsDisplayed()
            compose.onNodeWithText("登录其他工作空间").assertIsDisplayed()
            compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
            compose.onNodeWithContentDescription("工作空间操作").performClick()
            compose.onNodeWithText("同步电脑当前登录").assertIsDisplayed()
        } finally {
            models.clear()
            app.getSharedPreferences("codex-$device", 0).edit().clear().commit()
        }
    }
}
