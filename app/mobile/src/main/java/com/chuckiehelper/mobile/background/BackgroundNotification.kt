package com.chuckiehelper.mobile.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Multiple foreground services share one notification, while retaining independent lifetimes. */
object BackgroundNotification {
    private const val ID = 41
    private const val CHANNEL = "remotetool-background"
    private data class Feature(val service: Service, val text: String, val action: String?, val stop: PendingIntent?, val focus: android.os.Bundle? = null)
    private val features = linkedMapOf<String, Feature>()
    private var lastFocus: android.os.Bundle? = null

    @Synchronized
    fun update(context: Context, owner: String, text: String, focus: android.os.Bundle? = null) {
        val current = features[owner] ?: return
        features[owner] = current.copy(text = text, focus = focus)
        runCatching { refresh(context) }
    }

    @Synchronized
    fun start(service: Service, owner: String, text: String, action: String? = null, stop: PendingIntent? = null) {
        val manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "后台服务", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val old = features.put(owner, Feature(service, text, action, stop))
        try { service.startForeground(ID, build(service)) }
        catch (error: RuntimeException) {
            if (old == null) features.remove(owner) else features[owner] = old
            refresh(service)
            throw error
        }
        manager.cancel(73); manager.cancel(74)
    }

    @Synchronized
    fun finish(service: Service, owner: String) {
        // Detach before changing the shared notification, so stopping one service cannot remove it.
        service.stopForeground(Service.STOP_FOREGROUND_DETACH)
        if (features[owner]?.service === service) features.remove(owner)
        refresh(service)
    }

    private fun refresh(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (features.isEmpty()) { manager.cancel(ID); lastFocus = null } else manager.notify(ID, build(context))
    }

    private fun build(context: Context): Notification {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
        val open = PendingIntent.getActivity(context, 300, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val summary = features.values.joinToString(" · ") { it.text }
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("${context.applicationInfo.loadLabel(context.packageManager)} 后台服务").setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary)).setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
        features.values.filter { it.action != null && it.stop != null }.forEach { builder.addAction(0, it.action, it.stop) }
        val focus = features.values.firstNotNullOfOrNull { it.focus }
        if (focus != null) builder.addExtras(focus)
        else lastFocus?.let { previous ->
            // An ordinary foreground service notification may remain after the agent
            // leaves; explicitly dismiss its old island without stopping the service.
            runCatching {
                val params = org.json.JSONObject(previous.getString("miui.focus.param").orEmpty())
                val value = params.getJSONObject("param_v2")
                value.put("enableFloat", false)
                value.optJSONObject("param_island")?.put("dismissIsland", true)
                builder.addExtras(android.os.Bundle(previous).apply { putString("miui.focus.param", params.toString()) })
            }
        }
        lastFocus = focus?.let { android.os.Bundle(it) }
        return builder.build()
    }
}
