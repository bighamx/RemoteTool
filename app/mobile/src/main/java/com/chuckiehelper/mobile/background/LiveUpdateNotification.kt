package com.chuckiehelper.mobile.background

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Standard Android 16 Live Updates, kept compatible with older installed SDKs. */
internal object LiveUpdateNotification {
    val supported get() = Build.VERSION.SDK_INT >= 36
    fun apply(context: Context, notification: Notification, enabled: Boolean, status: String = "操控中"): Notification {
        if (!supported) return notification
        return runCatching {
            val builder = Notification.Builder.recoverBuilder(context, notification)
            Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, enabled)
            if (enabled) {
                Notification.Builder::class.java.methods.firstOrNull { it.name == "setShortCriticalText" && it.parameterCount == 1 }
                    ?.invoke(builder, status.take(7))
            }
            builder.build()
        }.getOrDefault(notification)
    }
    fun canPost(context: Context): Boolean = supported && runCatching {
        NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            .invoke(context.getSystemService(NotificationManager::class.java)) == true
    }.getOrDefault(false)
}
