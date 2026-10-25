package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable

private object PhoneEdition : AppEdition() {
    override val initialRoute = "更多"
}

@Composable internal fun rememberAppEdition(): AppEdition = PhoneEdition

// The phone edition has no chat/share receiver or update feed.
internal fun receiveEditionIntent(activity: ComponentActivity, intent: Intent) = Unit
