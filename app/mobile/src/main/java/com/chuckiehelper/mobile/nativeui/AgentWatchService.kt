package com.chuckiehelper.mobile.nativeui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.chuckiehelper.mobile.NativeActivity

/** Holds CPU/Wi-Fi only while an agent run or message queue needs the app alive. */
class AgentWatchService : LifecycleService() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

    override fun onDestroy() {
        releaseLocks()
        synchronized(Companion) { owners.clear() }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startForeground(NOTIFICATION_ID, notification())
            acquireLocks()
        } catch (error: RuntimeException) {
            Log.w("AgentWatchService", "Unable to keep agent connection in foreground", error)
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        // Restoring this service alone cannot restore the model's run/queue observer.
        return START_NOT_STICKY
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteTool:agent-watch")
                .also { it.acquire(2 * 60 * 60 * 1000L) }
        }
        if (wifiLock?.isHeld != true) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (wifi.isWifiEnabled) {
                @Suppress("DEPRECATION")
                wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteTool:agent-watch")
                    .also { it.acquire() }
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "会话保持", NotificationManager.IMPORTANCE_LOW).apply {
                description = "有执行中的任务或待发送消息时保持连接"
                setShowBadge(false)
            })
        }
        val content = PendingIntent.getActivity(
            this, 0, Intent(this, NativeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("RemoteTool 会话保持")
            .setContentText("正在保持与服务端的连接")
            .setContentIntent(content)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent-watch"
        private const val NOTIFICATION_ID = 41
        private val owners = mutableSetOf<String>()

        @Synchronized
        fun sync(context: Context, owner: String, needed: Boolean) {
            if (!needed) {
                if (owners.remove(owner) && owners.isEmpty())
                    context.stopService(Intent(context, AgentWatchService::class.java))
                return
            }
            if (!owners.add(owner)) return
            try {
                context.startForegroundService(Intent(context, AgentWatchService::class.java))
            } catch (error: IllegalStateException) {
                // Android can reject a foreground service launch after the app backgrounds.
                owners.remove(owner)
                Log.w("AgentWatchService", "Foreground service launch unavailable", error)
            } catch (error: SecurityException) {
                owners.remove(owner)
                Log.w("AgentWatchService", "Foreground service launch denied", error)
            }
        }
    }
}
