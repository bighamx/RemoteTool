package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

internal object AboveModelFieldPosition : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset = IntOffset(
        anchorBounds.left.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
        (anchorBounds.top - popupContentSize.height).coerceAtLeast(0))
}

@Composable
internal fun ProviderModelField(value: String, choices: List<String>, enabled: Boolean, onValueChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var width by remember { mutableIntStateOf(0) }
    var top by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    val density = LocalDensity.current
    val matches = remember(choices, query) { choices.filter { it.contains(query.trim(), ignoreCase = true) } }
    Box(Modifier.fillMaxWidth().onGloballyPositioned {
        width = it.size.width; top = it.boundsInWindow().top.toInt()
    }) {
        OutlinedTextField(value, { query = it; onValueChange(it); expanded = true },
            label = { Text("模型") }, placeholder = { Text("搜索或输入模型") },
            singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged {
                if (it.isFocused) { query = ""; expanded = true } else expanded = false
            }, trailingIcon = {
                Row {
                    if (value.isNotEmpty()) IconButton(enabled = enabled, onClick = {
                        focus.requestFocus(); query = ""; onValueChange(""); expanded = true
                    }) { Icon(Icons.Outlined.Close, "清空模型") }
                    IconButton(enabled = enabled, onClick = {
                        val open = !expanded
                        if (open) focus.requestFocus()
                        query = ""; expanded = open
                    }) { Icon(Icons.Outlined.ArrowDropDown, "展开模型列表") }
                }
            })
        if (expanded && enabled && width > 0 && top > 0) Popup(
            popupPositionProvider = AboveModelFieldPosition,
            onDismissRequest = { expanded = false },
            properties = PopupProperties(focusable = false),
        ) {
            // Fixed height while filtering prevents popup movement on each key.
            val height = with(density) { top.toDp().minus(8.dp).coerceAtLeast(1.dp).coerceAtMost(240.dp) }
            Surface(Modifier.width(with(density) { width.toDp() }).height(height),
                shape = MaterialTheme.shapes.extraSmall, shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainer) {
                LazyColumn {
                    if (matches.isEmpty()) item {
                        DropdownMenuItem(text = { Text("无匹配模型，可直接使用输入值") }, onClick = {}, enabled = false)
                    }
                    items(matches, key = { it }) { choice ->
                        DropdownMenuItem(text = { Text(choice) }, onClick = { onValueChange(choice); expanded = false })
                    }
                }
            }
        }
    }
}
