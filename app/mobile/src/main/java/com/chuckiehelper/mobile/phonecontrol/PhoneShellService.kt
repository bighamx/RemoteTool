package com.chuckiehelper.mobile.phonecontrol

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Runs in Shizuku's shell/root UserService process, never in the network listener. */
class PhoneShellService : IPhoneShell.Stub() {
    override fun uid() = Process.myUid()
    override fun destroy() { kotlin.system.exitProcess(0) }
    override fun execute(argv: Array<out String>, timeoutMs: Int): String = PhoneProcesses.run(argv.toList(), timeoutMs).toString()
    override fun screenshot(): ByteArray = PhoneProcesses.capture(listOf("screencap", "-p"))
}

internal object PhoneProcesses {
    fun capture(argv: List<String>): ByteArray {
        val process = ProcessBuilder(argv).start(); var data: ByteArray? = null; var failure: Exception? = null
        val reader = thread(isDaemon = true) {
            try { process.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break
                    if (out.size() + n > 8 * 1024 * 1024) throw IllegalStateException("Screenshot too large")
                    out.write(buffer, 0, n)
                }; data = out.toByteArray()
            } } catch (e: Exception) { failure = e; process.destroy() }
        }
        val errors = thread(isDaemon = true) { runCatching { process.errorStream.use { val buffer = ByteArray(2048); while (it.read(buffer) >= 0) { } } } }
        if (!process.waitFor(8, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("timeout", "特权截图超时") }
        reader.join(1000); errors.join(300)
        failure?.let { throw it }
        if (process.exitValue() != 0 || data?.isNotEmpty() != true) fail("screenshot_failed", "特权截图失败")
        // Binder transactions are bounded: resize/compress inside the privileged process.
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(data, 0, data!!.size) ?: fail("screenshot_failed", "截图格式无效")
        val scale = minOf(1.0, 1280.0 / maxOf(bitmap.width, bitmap.height))
        val small = android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        return java.io.ByteArrayOutputStream().use { out -> small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 65, out)
            if (small !== bitmap) small.recycle(); bitmap.recycle(); out.toByteArray() }
    }
    fun run(argv: List<String>, timeoutMs: Int): JSONObject {
        require(argv.isNotEmpty() && argv.size <= 128 && argv.sumOf { it.length } <= 128_000)
        val process = ProcessBuilder(argv).start()
        val out = StringBuilder(); val err = StringBuilder()
        fun read(input: InputStream, target: StringBuilder) = thread(isDaemon = true) {
            runCatching { input.bufferedReader().use { reader ->
                val chars = CharArray(4096)
                while (true) { val n = reader.read(chars); if (n < 0) break
                    synchronized(target) { if (target.length < 96_000) target.append(chars, 0, minOf(n, 96_000 - target.length)) }
                }
            } }
        }
        val a = read(process.inputStream, out); val b = read(process.errorStream, err)
        val completed = process.waitFor(timeoutMs.coerceIn(100, 30_000).toLong(), TimeUnit.MILLISECONDS)
        if (!completed) { process.destroy(); if (!process.waitFor(300, TimeUnit.MILLISECONDS)) process.destroyForcibly() }
        a.join(1000); b.join(1000)
        return JSONObject().put("exit_code", if (completed) process.exitValue() else -1)
            .put("stdout", synchronized(out) { out.toString() }).put("stderr", synchronized(err) { err.toString() })
            .put("timed_out", !completed)
    }
}

class PhonePrivilege(private val context: Context, private val settings: PhoneSettings) {
    @Volatile private var shell: IPhoneShell? = null
    @Volatile private var bound = false
    private val args = Shizuku.UserServiceArgs(ComponentName(context, PhoneShellService::class.java))
        .daemon(false).processNameSuffix("phone-shell").version(context.packageManager.getPackageInfo(context.packageName, 0).versionCode)
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) { if (bound) shell = IPhoneShell.Stub.asInterface(binder) }
        override fun onServiceDisconnected(name: ComponentName?) { shell = null; bound = false }
    }
    fun available() = settings.shizuku && runCatching { Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    @Synchronized fun bind() { if (available() && !bound) { bound = true; try { Shizuku.bindUserService(args, connection) } catch (e: Exception) { bound = false; throw e } } }
    @Synchronized fun close() { if (bound) runCatching { Shizuku.unbindUserService(args, connection, true) }; bound = false; shell = null }
    fun status() = JSONObject().put("shizuku", available()).put("shizuku_uid", runCatching { if (available()) Shizuku.getUid() else -1 }.getOrDefault(-1))
        .put("root_enabled", settings.root).put("user_service", shell != null)
    fun screenshot(backend: String): ByteArray {
        if (backend != "root" && available()) {
            if (shell == null) execute(listOf("true"), "shizuku")
            return shell?.screenshot() ?: fail("backend_unavailable", "Shizuku 未就绪")
        }
        if (settings.root && backend != "shizuku") return PhoneProcesses.capture(listOf("su", "-c", "screencap -p"))
        fail("backend_unavailable", "没有可用截图后端")
    }
    fun execute(argv: List<String>, backend: String = "auto", timeoutMs: Int = 10_000): JSONObject {
        if (backend !in setOf("auto", "shizuku", "root")) fail("invalid_arguments", "backend must be auto, shizuku or root")
        if (backend != "root" && available()) {
            if (shell == null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { runCatching { bind() } }
                repeat(30) { if (shell == null) Thread.sleep(100) }
            }
            val active = shell ?: fail("backend_unavailable", "Shizuku UserService 尚未就绪")
            return JSONObject(active.execute(argv.toTypedArray(), timeoutMs)).put("backend", "shizuku").put("uid", active.uid())
        }
        if (backend != "shizuku" && settings.root) {
            val quoted = argv.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
            return PhoneProcesses.run(listOf("su", "-c", quoted), timeoutMs).put("backend", "root")
        }
        fail("backend_unavailable", "请启用并授权 Shizuku 或 Root")
    }
}
