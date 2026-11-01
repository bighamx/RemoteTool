package com.chuckiehelper.mobile.nativeui

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

private class PhoneEdition(private val updates: AppUpdateModel) : AppEdition() {
    override val initialRoute = "更多"
    override val showUpdates = true
    override val updateSubtitle get() = updates.subtitle
    override fun openUpdates() = updates.open()
    override fun reloadSettings() = updates.reloadCloudSettings()
    @Composable override fun UpdateHost() { AppUpdateHost(updates) }
    override fun pageTitle(route: String) = if (route == "更多") "手机操控" else route
}

@Composable internal fun rememberAppEdition(): AppEdition {
    val updates: AppUpdateModel = androidx.lifecycle.viewmodel.compose.viewModel()
    return remember(updates) { PhoneEdition(updates) }
}

// The phone edition has no chat/share receiver.
internal fun receiveEditionIntent(activity: ComponentActivity, intent: Intent) = Unit
