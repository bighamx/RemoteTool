package com.chuckiehelper.mobile.background

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/** Standard Android 16 Live Updates, kept compatible with older installed SDKs. */
internal object LiveUpdateNotification {
    val supported get() = Build.VERSION.SDK_INT >= 36
    fun apply(context: Context, notification: Notification, enabled: Boolean, status: String = "操控中"): Notification {
        // HyperOS prefers the generic promoted template when both are requested, hiding
        // our Xiaomi left/right text layout. Select one renderer for each notification.
        val customIsland = runCatching {
            org.json.JSONObject(notification.extras.getString("miui.focus.param").orEmpty())
                .optJSONObject("param_v2")?.optJSONObject("param_island")?.let {
                    !it.optBoolean("dismissIsland")
                } == true
        }.getOrDefault(false)
        val promote = enabled && !customIsland
        notification.extras.putBoolean("android.requestPromotedOngoing", promote)
        if (!promote) notification.extras.remove("android.shortCriticalText")
        if (!supported) return notification
        return runCatching {
            val builder = Notification.Builder.recoverBuilder(context, notification)
            Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, promote)
            if (promote) {
                Notification.Builder::class.java.methods.firstOrNull { it.name == "setShortCriticalText" && it.parameterCount == 1 }
                    ?.invoke(builder, status.codePoints().limit(7).toArray().let { String(it, 0, it.size) })
            }
            builder.build()
        }.getOrDefault(notification)
    }
    fun canPost(context: Context): Boolean = supported && runCatching {
        NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            .invoke(context.getSystemService(NotificationManager::class.java)) == true
    }.getOrDefault(false)
}
