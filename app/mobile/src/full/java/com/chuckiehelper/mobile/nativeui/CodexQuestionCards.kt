package com.chuckiehelper.mobile.nativeui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CodexQuestionAnswerCard(entries: List<JSONObject>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow, border = BorderStroke(1.dp, colors.outlineVariant)) {
        SelectionContainer {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Icon(Icons.Outlined.HelpOutline, null, Modifier.size(18.dp), tint = colors.primary)
                    Text("询问", style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
                entries.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = colors.outlineVariant)
                    val answer = entry.optString("answer")
                    val status = entry.optString("status")
                    val answered = answer.isNotBlank() || status == "answered"
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.optString("question"), style = MaterialTheme.typography.titleSmall)
                        val choices = entry.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                (0 until choices.length()).forEach { choiceIndex ->
                                    val choice = choices.optString(choiceIndex)
                                    val picked = choice == answer
                                    Surface(shape = MaterialTheme.shapes.small,
                                        color = if (picked) colors.primaryContainer else colors.surfaceContainerHighest) {
                                        Text(choice, Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (picked) colors.onPrimaryContainer else colors.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Surface(shape = MaterialTheme.shapes.medium,
                            color = if (answered) colors.secondaryContainer else colors.surfaceContainerHighest) {
                            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(if (answered) Icons.Outlined.CheckCircle else Icons.Outlined.HelpOutline,
                                    null, Modifier.size(18.dp), tint = if (answered) colors.onSecondaryContainer else colors.onSurfaceVariant)
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(if (answered) "你的回答" else if (status == "declined") "已取消" else "未回答",
                                        style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                    if (answer.isNotBlank()) Text(answer, style = MaterialTheme.typography.bodyMedium,
                                        color = colors.onSecondaryContainer)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
