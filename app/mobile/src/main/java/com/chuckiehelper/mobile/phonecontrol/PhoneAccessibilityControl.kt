package com.chuckiehelper.mobile.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** User opted-in restoration; never overwrites other apps' enabled services. */
internal object PhoneAccessibilityControl {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private var pending: Job? = null
    private var lastAttempt = 0L
    @Volatile var busy = false; private set
    @Volatile var status = ""; private set
    fun setWanted(context: Context, wanted: Boolean, onFinished: (() -> Unit)? = null) {
        PhoneSettings(context).accessibilityWanted = wanted
        request(context, force = true, onFinished = onFinished)
    }
    @Synchronized fun request(context: Context, force: Boolean = false, onFinished: (() -> Unit)? = null) {
        val app = context.applicationContext
        val settings = PhoneSettings(app)
        if (!force && (!settings.accessibilityWanted || PhoneAccessibility.current != null || pending?.isActive == true || lastAttempt != 0L && SystemClock.elapsedRealtime() - lastAttempt < 60_000)) return
        pending = scope.launch {
            gate.withLock {
                val wanted = settings.accessibilityWanted
                if (!force && (!wanted || PhoneAccessibility.current != null)) return@withLock
                busy = true; lastAttempt = SystemClock.elapsedRealtime()
                try {
                    status = if (wanted) "正在恢复无障碍服务" else "正在关闭无障碍服务"
                    if (!wanted) {
                        PhoneAccessibility.current?.let { service -> Handler(Looper.getMainLooper()).post { service.disableSelf() } }
                        repeat(10) { if (PhoneAccessibility.current != null) delay(100) }
                        if (PhonePermissionStatus.accessibilityConfigured(app) == false) { status = "已关闭"; return@withLock }
                    }
                    val privilege = PhoneEngine.get(app).privilege
                    val backends = buildList { if (privilege.available()) add("shizuku"); if (settings.root) add("root") }
                    if (backends.isEmpty()) { status = "需要授权 Root / Shizuku，或前往系统设置开启"; return@withLock }
                    val own = ComponentName(app, PhoneAccessibility::class.java).flattenToString()
                    var lastError: Exception? = null
                    for (backend in backends) {
                        try {
                            fun command(vararg args: String): String {
                                val result = privilege.execute(args.toList(), backend, 10_000)
                                check(result.optInt("exit_code", -1) == 0 && !result.optBoolean("timed_out")) { "系统未允许修改无障碍设置" }
                                return result.optString("stdout").trim()
                            }
                            if (settings.accessibilityWanted != wanted) return@withLock
                            val before = command("settings", "--user", "current", "get", "secure", "enabled_accessibility_services")
                            // Cycle only our own configured-but-unbound component to trigger rebinding.
                            if (wanted && PhoneAccessibility.current == null && contains(before, own)) {
                                command("settings", "--user", "current", "put", "secure", "enabled_accessibility_services", merge(before, own, false))
                                delay(250)
                            }
                            val current = command("settings", "--user", "current", "get", "secure", "enabled_accessibility_services")
                            if (settings.accessibilityWanted != wanted) return@withLock
                            command("settings", "--user", "current", "put", "secure", "enabled_accessibility_services", merge(current, own, wanted))
                            if (wanted) command("settings", "--user", "current", "put", "secure", "accessibility_enabled", "1")
                            repeat(40) { if (settings.accessibilityWanted == wanted && (PhoneAccessibility.current != null) != wanted) delay(100) }
                            if (settings.accessibilityWanted != wanted) return@withLock
                            if ((PhoneAccessibility.current != null) == wanted) { status = if (wanted) "已开启 · 运行中" else "已关闭"; return@withLock }
                            check(PhonePermissionStatus.accessibilityGranted(app) == wanted) { "系统未保留无障碍授权" }
                            status = if (wanted) "已授权 · 等待系统连接服务" else "已关闭"; return@withLock
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { lastError = error }
                    }
                    status = lastError?.message?.take(100) ?: "恢复失败，请前往系统无障碍设置"
                } finally { busy = false; if (onFinished != null) withContext(Dispatchers.Main) { onFinished() } }
            }
        }
    }
    internal fun contains(value: String, own: String): Boolean = value.split(':').any { ComponentName.unflattenFromString(it) == ComponentName.unflattenFromString(own) }
    internal fun merge(value: String, own: String, enabled: Boolean): String {
        val target = ComponentName.unflattenFromString(own) ?: error("Invalid accessibility component")
        val others = value.split(':').filter { it.isNotBlank() && it != "null" && ComponentName.unflattenFromString(it) != target }.distinct()
        return (if (enabled) others + own else others).joinToString(":")
    }
}
