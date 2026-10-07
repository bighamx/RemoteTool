package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@Composable
internal fun ComposerActionGlyph(stopping: Boolean, tint: Color) {
    if (stopping) Box(Modifier.size(14.dp)
        .background(tint, RoundedCornerShape(3.dp))
        .semantics { contentDescription = "停止任务" })
    else Icon(Icons.Rounded.ArrowUpward, "发送消息", Modifier.size(24.dp), tint = tint)
}
