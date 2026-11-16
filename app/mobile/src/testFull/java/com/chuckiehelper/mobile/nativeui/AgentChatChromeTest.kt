package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.json.JSONObject
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
class AgentChatChromeTest {
    @get:Rule val compose = createComposeRule()
    private val names = listOf("Codex", "Hermes", "Claude Code")

    @Composable private fun Header(title: String, busy: Boolean = false, isSessionList: Boolean = false) {
        AgentPageHeader(title, busy = busy, isSessionList = isSessionList) {
            repeat(3) { index ->
                IconButton({}, Modifier.size(36.dp).testTag("action-$index")) {
                    Icon(Icons.Outlined.MoreVert, "action $index", Modifier.size(22.dp))
                }
            }
        }
    }

    @Test fun listsKeepTheSameHeaderSearchCardAndTimeGeometryWithDifferentControls() {
        var agent by mutableIntStateOf(0)
        compose.setContent { MaterialTheme { Surface(Modifier.width(360.dp).height(640.dp)) {
            Column {
                Header("${names[agent]} 会话", busy = agent == 0, isSessionList = true)
                AgentListInfo("不同供应商 · a-very-long-model-name", if (agent == 0) "5h 100% · 周 22%" else "") {
                    if (agent != 2) AgentHeaderChip(if (agent == 0) "项目" else "长名称 Profile", false, {})
                }
                AgentSessionSearch("") {}
                Box(Modifier.padding(horizontal = 12.dp).testTag("session-card")) {
                    AgentSessionCard("这是长度不同的会话名称 ${names[agent]}", if (agent == 1) "短摘要" else "较长摘要 ".repeat(20),
                        if (agent == 0) "D:/GIT/a-very-long-project-name" else names[agent], "13:16", false, false,
                        if (agent == 0) "运行中" else null, if (agent == 0) .3f else null, {}, {}) {
                        IconButton({}, Modifier.size(32.dp)) { Icon(Icons.Outlined.MoreVert, "会话操作") }
                    }
                }
            }
        } } }
        var baseline: List<Rect>? = null
        names.indices.forEach { index ->
            compose.runOnUiThread { agent = index }
            compose.waitForIdle()
            val header = compose.onNodeWithTag("agent-page-header").fetchSemanticsNode().boundsInRoot
            repeat(3) { action ->
                val bounds = compose.onNodeWithTag("action-$action").fetchSemanticsNode().boundsInRoot
                assertEquals("Action is vertically centered with the title row", header.center.y, bounds.center.y, 1f)
            }
            val geometry = listOf("agent-page-header", "action-0", "action-1", "action-2", "agent-session-search", "session-card")
                .map { compose.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot } + compose.onNodeWithText("13:16", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            if (baseline == null) baseline = geometry else assertEquals("${names[index]} must use the same list skeleton", baseline, geometry)
        }
    }

    @Test fun conversationsKeepContentStartStableAcrossQuotaPermissionAndLoadingAtLargeFont() {
        var agent by mutableIntStateOf(0)
        compose.setContent { MaterialTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.35f)) {
                Column(Modifier.width(360.dp).height(640.dp)) {
                    Header("很长的会话名称，需要省略显示 · ${names[agent]}")
                    AgentContextInfo(if (agent == 2) null else JSONObject().put("available", true).put("tokens", 123400).put("limit", 1000000),
                        "供应商 · model-name · 中思考", quota = if (agent == 0) "5h 90% · 周 50%" else "",
                        permission = if (agent == 1) "" else "完全访问")
                    Box(Modifier.fillMaxWidth().height(100.dp).testTag("chat-body"))
                }
            }
        } }
        var baseline: Rect? = null
        names.indices.forEach { index ->
            compose.runOnUiThread { agent = index }
            compose.waitForIdle()
            val body = compose.onNodeWithTag("chat-body").fetchSemanticsNode().boundsInRoot
            if (baseline == null) baseline = body else assertEquals("Metadata availability must not move the conversation", baseline, body)
            compose.onNodeWithText("供应商 · model-name · 中思考").assertExists()
            val runtime = compose.onNodeWithText("供应商 · model-name · 中思考", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val usage = compose.onNodeWithText(if (index == 2) "暂无数据" else "123.4K / 1M · 12%", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("Provider/model precedes context usage", runtime.top < usage.top)
        }
    }
}
