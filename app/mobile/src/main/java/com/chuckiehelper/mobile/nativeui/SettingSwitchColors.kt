package com.chuckiehelper.mobile.nativeui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable

/** Disabled means read-only, not unchecked. Keep the checked track visibly coloured. */
@Composable
internal fun settingSwitchColors(): SwitchColors = SwitchDefaults.colors(
    disabledCheckedTrackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
    disabledCheckedThumbColor = MaterialTheme.colorScheme.onPrimary,
    disabledCheckedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
    disabledCheckedIconColor = MaterialTheme.colorScheme.primary,
)
