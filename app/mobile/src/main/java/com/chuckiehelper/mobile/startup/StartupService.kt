package com.chuckiehelper.mobile.startup

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import com.chuckiehelper.mobile.background.BackgroundNotification
import com.chuckiehelper.mobile.nativeui.NativeApi
import com.chuckiehelper.mobile.phonecontrol.PhoneControlService
import com.chuckiehelper.mobile.phonecontrol.PhoneEngine
import com.chuckiehelper.mobile.tunnel.TunnelRuntime
import com.chuckiehelper.mobile.tunnel.TunnelSettings
import kotlinx.coroutines.*

class StartupService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var restore: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") {
            pausedPhone = true; pausedTunnel = true
            StartupSettings(this).result = "已停止等待恢复"; stopSelf(); return START_NOT_STICKY
        }
        val settings = StartupSettings(this)
        if (!settings.enabled) { stopSelf(); return START_NOT_STICKY }
        val stop = PendingIntent.getService(this, 207, Intent(this, StartupService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { BackgroundNotification.start(this, "boot-startup", "恢复后台服务", "停止恢复", stop) }
        catch (_: Exception) { settings.result = "系统未允许后台启动，请打开 App 后重试"; stopSelf(); return START_NOT_STICKY }
        running = true
        if (restore?.isActive == true) return START_NOT_STICKY
        settings.result = "正在恢复后台服务"
        restore = scope.launch {
            try { restoreServices(settings) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { settings.result = "恢复未完成，请在开机自启页面检查配置" }
            finally { stopSelf() }
        }
        return START_NOT_STICKY
    }
    private suspend fun restoreServices(settings: StartupSettings) {
        val context = applicationContext
        var phoneDone = !settings.phoneTools
        var tunnelDone = !settings.portForward
        val outcomes = mutableListOf<String>()
        val connections = mutableMapOf<String, NativeApi>()
        var localStarted = false
        var attempt = 0
        while (currentCoroutineContext().isActive && settings.enabled && (!phoneDone || !tunnelDone)) {
            if (!settings.phoneTools || pausedPhone) phoneDone = true
            if (!settings.portForward || pausedTunnel) tunnelDone = true
            var pending = false
            if (!phoneDone) {
                val phone = PhoneEngine.get(context).settings
                if (phone.direct && !localStarted) {
                    PhoneControlService.start(context, rememberForBoot = false)
                    localStarted = true
                }
                if (!settings.phoneRelay) {
                    outcomes.add(if (localStarted) "已请求启动手机操控" else "手机操控未配置接入方式")
                    phoneDone = true
                } else {
                    val target = settings.target("phone")
                    if (target == null) { outcomes.add("手机操控转发未配置电脑"); phoneDone = true }
                    else try {
                        val api = connections[target.id] ?: withTimeout(25_000) { startupConnection(context, target) }.also { connections[target.id] = it }
                        if (!pausedPhone && settings.phoneTools) PhoneControlService.attachRelay(context, api.base, api.cookie())
                        outcomes.add("已请求恢复手机操控与转发通道"); phoneDone = true
                    } catch (required: StartupLoginRequired) { outcomes.add("手机操控：请登录目标电脑"); phoneDone = true }
                    catch (cancelled: CancellationException) { if (!currentCoroutineContext().isActive) throw cancelled; pending = true }
                    catch (_: Exception) { pending = true }
                }
            }
            if (!tunnelDone) {
                val target = settings.target("tunnel")
                val rules = target?.let { TunnelSettings(context).rules(it.id) }.orEmpty()
                if (target == null || rules.isEmpty()) { outcomes.add("端口转发未配置"); tunnelDone = true }
                else try {
                    val api = connections[target.id] ?: withTimeout(25_000) { startupConnection(context, target) }.also { connections[target.id] = it }
                    if (!pausedTunnel && settings.portForward && !TunnelRuntime.status.value.running)
                        TunnelRuntime.start(context, target.id, target.name, api, rules)
                    outcomes.add("已请求恢复端口转发"); tunnelDone = true
                } catch (required: StartupLoginRequired) { outcomes.add("端口转发：请登录目标电脑"); tunnelDone = true }
                catch (cancelled: CancellationException) { if (!currentCoroutineContext().isActive) throw cancelled; pending = true }
                catch (_: Exception) { pending = true }
            }
            if (pending && (!phoneDone || !tunnelDone)) {
                settings.result = (outcomes + "等待网络或电脑上线").joinToString("；")
                delay(minOf(30_000L, 3000L shl minOf(attempt++, 4)))
            }
        }
        settings.result = outcomes.joinToString("；").ifBlank { "恢复已停止" }
    }
    override fun onDestroy() {
        running = false; scope.cancel(); BackgroundNotification.finish(this, "boot-startup"); super.onDestroy()
    }
    companion object {
        @Volatile private var pausedPhone = false
        @Volatile private var pausedTunnel = false
        @Volatile var running = false; private set
        val phonePaused get() = pausedPhone
        fun pausePhone() { pausedPhone = true }
        fun pauseTunnel() { pausedTunnel = true }
        fun request(context: Context) {
            pausedPhone = false; pausedTunnel = false
            try { context.startForegroundService(Intent(context, StartupService::class.java)) }
            catch (_: Exception) { StartupSettings(context).result = "系统未允许开机恢复，请打开 App 后重试" }
        }
        fun stop(context: Context) {
            pausedPhone = true; pausedTunnel = true
            StartupSettings(context).result = "已停止等待恢复"
            context.stopService(Intent(context, StartupService::class.java))
        }
    }
}
