@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared chrome for Codex, Hermes and Claude, in both list and conversation mode. */
@Composable
internal fun AgentPageHeader(
    title: String,
    busy: Boolean = false,
    onTitleClick: (() -> Unit)? = null,
    isSessionList: Boolean = false,
    actions: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 36.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 12.dp)
            .testTag("agent-page-header"), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).let { if (onTitleClick != null) it.clickable(onClick = onTitleClick) else it },
                style = if (isSessionList) MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, lineHeight = 20.sp)
                    else MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (busy) CircularProgressIndicator(Modifier.padding(horizontal = 6.dp).size(14.dp), strokeWidth = 2.dp)
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
internal fun AgentListInfo(
    runtime: String,
    quota: String = "",
    onQuotaClick: () -> Unit = {},
    controls: @Composable RowScope.() -> Unit = {},
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
        Row(Modifier.fillMaxWidth().heightIn(min = 24.dp).padding(horizontal = 12.dp).testTag("agent-list-info"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(runtime.ifBlank { "正在读取运行信息" }, Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (quota.isNotBlank()) Text(quota, Modifier.clickable(onClick = onQuotaClick),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            controls()
        }
    }
}

@Composable
internal fun AgentSessionSearch(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value, onValueChange,
        placeholder = { Text("搜索会话或历史消息", style = MaterialTheme.typography.bodySmall) },
        leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(18.dp)) },
        trailingIcon = {
            if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }) {
                Icon(Icons.Outlined.Close, "清空搜索", Modifier.size(18.dp))
            }
        },
        singleLine = true, textStyle = MaterialTheme.typography.bodySmall,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).heightIn(min = 52.dp)
            .testTag("agent-session-search"))
}

@Composable
internal fun AgentSessionCard(
    title: String,
    preview: String,
    metadata: String,
    time: String?,
    pinned: Boolean,
    archived: Boolean,
    activity: String?,
    contextRatio: Float?,
    onClick: () -> Unit,
    onActivityClick: () -> Unit,
    menu: @Composable () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().alpha(if (archived) .55f else 1f)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).testTag("session-heading"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (pinned) Icon(Icons.Outlined.PushPin, "已置顶", Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                activity?.let {
                    Surface(Modifier.clickable(enabled = it == "待核对", onClick = onActivityClick),
                        color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(it, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
                CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 32.dp) { menu() }
            }
            Text(preview.ifBlank { "暂无消息" }, Modifier.testTag("session-preview"), style = MaterialTheme.typography.bodySmall,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth().heightIn(min = 18.dp).testTag("session-footer"), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiddleEllipsisText(metadata, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                time?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (contextRatio != null) LinearProgressIndicator(progress = { contextRatio.coerceIn(0f, 1f) }, gapSize = 0.dp,
                modifier = Modifier.fillMaxSize(), drawStopIndicator = {},
                color = if (contextRatio >= .9f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        }
    }
}
