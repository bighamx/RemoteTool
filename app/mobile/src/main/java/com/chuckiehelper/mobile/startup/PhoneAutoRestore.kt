package com.chuckiehelper.mobile.startup

import android.content.Context
import com.chuckiehelper.mobile.phonecontrol.*
import kotlinx.coroutines.*

/** Restores prior user intent when this process wakes; never starts the process itself. */
object PhoneAutoRestore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile var status: String = ""; private set
    @Synchronized fun cancel() { job?.cancel(); job = null; status = "" }
    @Synchronized fun request(context: Context) {
        val app = context.applicationContext
        val settings = PhoneSettings(app)
        if (!settings.autoRestore || !settings.desiredRunning || job?.isActive == true) return
        val relay = settings.restoreRelay
        if (PhoneControlService.running && (relay == null || PhoneControlService.relayBase != null)) return
        // Request foreground startup while the wake/resume context is available.
        if (!PhoneControlService.running && !PhoneControlService.starting && settings.direct) {
            try { PhoneControlService.start(context, rememberForBoot = false, restoring = true) }
            catch (_: Exception) { status = "系统暂未允许恢复，打开 App 后将再次尝试"; return }
        }
        if (relay == null) { status = if (settings.direct) "已请求恢复手机操控" else "尚未配置接入方式"; return }
        val target = StartupSettings(app).target("phone")
        if (target == null || relay.trimEnd('/') !in target.addresses.map { it.trimEnd('/') }) {
            status = "转发目标已变化，请在手机操控页面重新选择"; return
        }
        job = scope.launch {
            var attempt = 0
            while (isActive && settings.autoRestore && settings.desiredRunning) {
                try {
                    status = "正在恢复转发连接"
                    val api = withTimeout(25_000) { startupConnection(app, target) }
                    ensureActive()
                    if (!settings.autoRestore || !settings.desiredRunning) break
                    if (!restoreRelayIfWanted(app, settings, api.base, api.cookie())) break
                    status = "已请求恢复手机操控与转发连接"; break
                } catch (cancelled: CancellationException) { if (!isActive) throw cancelled }
                catch (_: StartupLoginRequired) { status = "请登录转发目标电脑后重试恢复"; break }
                catch (_: Exception) { status = "等待网络或电脑上线后恢复" }
                delay(minOf(30_000L, 3_000L shl minOf(attempt++, 4)))
            }
        }
    }
    @Synchronized private fun restoreRelayIfWanted(context: Context, settings: PhoneSettings, base: String, cookie: String): Boolean {
        if (!settings.autoRestore || !settings.desiredRunning) return false
        PhoneControlHost.resumeBootRestore()
        if (PhoneControlService.running || PhoneControlService.starting) PhoneControlService.attachRelay(context, base, cookie)
        else PhoneControlService.start(context, base, cookie, rememberForBoot = false, restoring = true)
        return true
    }
}
