package com.chuckiehelper.mobile.phonecontrol

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.chuckiehelper.mobile.background.BackgroundNotification

class PhoneControlService : Service() {
    private var listener: PhoneHttpServer? = null
    private var relay: PhoneRelay? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stop(this); return START_NOT_STICKY }
        val engine = PhoneEngine.get(this)
        if (intent?.getBooleanExtra("boot_restore", false) == true && PhoneControlHost.bootRestorePaused()) {
            if (!running) stopSelf()
            return START_NOT_STICKY
        }
        if (!engine.settings.enabled) { starting = false; stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 2, Intent(this, PhoneControlService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            BackgroundNotification.start(this, "phone-tools", "手机操控", "停止手机操控", stop)
            engine.agentUi.start()
            if (intent?.action != "attach_relay" || !running) {
                listener?.stop(); listener = null
                if (engine.settings.direct) listener = PhoneHttpServer(engine).also { it.start(15_000, false) }
                runCatching { engine.privilege.bind() }
            }
            relay?.close(); relay = null; relayBase = null
            intent?.getStringExtra("relay_url")?.let { url ->
                relayBase = url
                relay = PhoneRelay(engine, url, intent.getStringExtra("relay_cookie").orEmpty())
            }
            running = true; starting = false; error = null
        } catch (e: Exception) { error = e.message ?: "启动失败"; starting = false; running = false; engine.stop(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onDestroy() { PhoneEngine.get(this).agentUi.close(); BackgroundNotification.finish(this, "phone-tools"); PhoneEngine.get(this).stop(); listener?.stop(); relay?.close(); relayBase = null; running = false; starting = false; super.onDestroy() }
    companion object {
        @Volatile var running = false; private set
        @Volatile var starting = false; private set
        @Volatile var error: String? = null; private set
        @Volatile var relayBase: String? = null; private set
        fun start(context: Context, relayUrl: String? = null, cookie: String? = null, rememberForBoot: Boolean = true) {
            if (running || starting) return
            val settings = PhoneEngine.get(context).settings
            require(settings.direct || !relayUrl.isNullOrBlank()) { "请先选择接入方式" }
            if (rememberForBoot) runCatching { PhoneControlHost.rememberForBoot(context, relayUrl) }
            starting = true; error = null; settings.enabled = true
            try {
                context.startForegroundService(Intent(context, PhoneControlService::class.java).apply {
                    putExtra("relay_url", relayUrl); putExtra("relay_cookie", cookie); putExtra("boot_restore", !rememberForBoot)
                })
            } catch (e: Exception) {
                starting = false; settings.enabled = false; error = e.message ?: "启动失败"
                throw e
            }
        }
        fun attachRelay(context: Context, url: String, cookie: String) {
            if (PhoneControlHost.bootRestorePaused()) return
            if (!running && !starting) { start(context, url, cookie, rememberForBoot = false); return }
            context.startForegroundService(Intent(context, PhoneControlService::class.java).setAction("attach_relay").apply {
                putExtra("relay_url", url); putExtra("relay_cookie", cookie); putExtra("boot_restore", true)
            })
        }
        fun stop(context: Context) {
            PhoneControlHost.pauseBootRestore()
            starting = false; running = false; PhoneEngine.get(context).stop(); context.stopService(Intent(context, PhoneControlService::class.java))
        }
    }
}
