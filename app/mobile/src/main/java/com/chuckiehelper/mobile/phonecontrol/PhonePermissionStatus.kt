package com.chuckiehelper.mobile.phonecontrol

import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings

internal data class PhonePermissionState(
    val notificationGranted: Boolean? = null, val notificationRunning: Boolean = false,
    val accessibilityGranted: Boolean? = null, val accessibilityRunning: Boolean = false,
    val overlay: Boolean? = null, val files: Boolean? = null, val writeSettings: Boolean? = null,
    val policy: Boolean? = null, val usage: Boolean? = null, val install: Boolean? = null,
    val backgroundLocation: Boolean? = null, val granted: Set<String> = emptySet()
) {
    val notificationLabel get() = label(notificationGranted)
    val accessibilityLabel get() = serviceLabel(accessibilityGranted, accessibilityRunning)
    companion object {
        fun label(granted: Boolean?): String = when (granted) { true -> "已授权"; false -> "未授权"; null -> "状态未知" }
        fun serviceLabel(granted: Boolean?, running: Boolean) = if (granted == false) "未授权" else if (running) "已授权 · 运行中" else when (granted) { true -> "已授权 · 服务未连接"; false -> "未授权"; null -> "授权状态未知" }
    }
}

internal object PhonePermissionStatus {
    fun notificationGranted(context: Context): Boolean? = runCatching {
        val component = ComponentName(context, PhoneNotifications::class.java)
        if (Build.VERSION.SDK_INT >= 27) context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component)
        else Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
    }.getOrNull()
    fun accessibilityGranted(context: Context): Boolean? = runCatching {
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            accessibilityConfigured(context) == true
    }.getOrNull()
    fun accessibilityConfigured(context: Context): Boolean? = runCatching {
        val component = ComponentName(context, PhoneAccessibility::class.java)
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty().split(':')
            .any { ComponentName.unflattenFromString(it) == component }
    }.getOrNull()
    fun read(context: Context): PhonePermissionState {
        fun query(block: () -> Boolean) = runCatching(block).getOrNull()
        val permissions = runCatching { context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
            .filter { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }.toSet() }.getOrDefault(emptySet())
        return PhonePermissionState(notificationGranted(context), PhoneNotifications.current != null,
            accessibilityGranted(context), PhoneAccessibility.current != null,
            query { Settings.canDrawOverlays(context) }, query { Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager() },
            query { Settings.System.canWrite(context) }, query { context.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted },
            query { context.getSystemService(AppOpsManager::class.java).checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED },
            query { context.packageManager.canRequestPackageInstalls() },
            query { Build.VERSION.SDK_INT < 29 || context.checkSelfPermission("android.permission.ACCESS_BACKGROUND_LOCATION") == PackageManager.PERMISSION_GRANTED }, permissions)
    }
}
