package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal enum class ChatRowKind { Message, Execution, Approval, Empty, History, Bottom }
internal data class ChatTimelineRow(val key: String, val kind: ChatRowKind, val message: ChatMessageItem? = null)

/** Native history stays chronological; only the visual list is indexed from its bottom. */
internal fun chatTimelineRows(messages: List<ChatMessageItem>, execution: Boolean, approval: Boolean,
    empty: Boolean = false): List<ChatTimelineRow> = buildList {
    add(ChatTimelineRow("conversation-bottom", ChatRowKind.Bottom))
    if (empty) add(ChatTimelineRow("conversation-empty", ChatRowKind.Empty))
    if (approval) add(ChatTimelineRow("approval", ChatRowKind.Approval))
    if (execution) add(ChatTimelineRow("execution", ChatRowKind.Execution))
    messages.asReversed().forEach { add(ChatTimelineRow(it.key, ChatRowKind.Message, it)) }
    add(ChatTimelineRow("history-start", ChatRowKind.History))
}

@Composable
internal fun ChatTimelineList(rows: List<ChatTimelineRow>, scroll: LazyListState, modifier: Modifier = Modifier,
    content: @Composable LazyItemScope.(ChatTimelineRow) -> Unit) {
    LazyColumn(modifier, state = scroll, reverseLayout = true,
        contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(rows, key = { it.key }, contentType = { it.kind }) { row -> content(row) }
    }
}
