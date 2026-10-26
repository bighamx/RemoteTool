package com.chuckiehelper.mobile.nativeui

import androidx.compose.runtime.Composable

/** Variant wiring only. Shared pages and services must never depend on full-only features. */
internal abstract class AppEdition {
    abstract val initialRoute: String
    open val showUpdates: Boolean = false
    open val updateSubtitle: String = ""
    open fun reloadSettings() = Unit
    open fun openUpdates() = Unit
    open fun normalizeRoute(route: String): String = route
    open fun ownsToolbar(route: String): Boolean = false
    open fun pageTitle(route: String): String = route
    open fun shareId(deviceId: String?): String? = null
    open fun shareRoute(deviceId: String?): String? = null
    open fun choosingShare(deviceId: String?): Boolean = false
    @Composable open fun UpdateHost() = Unit
    @Composable open fun Navigation(route: String, deviceName: String, onRoute: (String) -> Unit) = Unit
    @Composable open fun ShareChooser(api: NativeApi, deviceId: String, deviceName: String, changeDevice: () -> Unit) = Unit
    @Composable open fun MoreItems(onDetail: (String) -> Unit) = Unit
    @Composable open fun Page(route: String, api: NativeApi, deviceId: String, error: (String) -> Unit,
        onDetail: (String) -> Unit, onClose: () -> Unit): Boolean = false
}
