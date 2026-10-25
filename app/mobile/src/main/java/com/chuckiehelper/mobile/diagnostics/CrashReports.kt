package com.chuckiehelper.mobile.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.chuckiehelper.mobile.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Local, bounded crash evidence. Never sends requests or restarts the process. */
internal object CrashReports {
    private const val maxReports = 20
    private const val maxBytes = 1024 * 1024
    private val installed = AtomicBoolean(false)
    private val crashing = AtomicBoolean(false)
    private val crumbs = ArrayDeque<String>()
    private var started = SystemClock.elapsedRealtime()
    @Volatile private var screen = "startup"
    private val secret = Regex("(?i)((?:bearer|basic)\\s+|(?:api[_-]?key|password|secret|token|access_token|refresh_token|id_token|authorization)[\"']?\\s*[=:]\\s*[\"']?(?:(?:bearer|basic)\\s+)?)[^\\s,;\"']+")
    private val cookies = Regex("(?i)((?:set-cookie|cookie)[\"']?\\s*[:=]\\s*).*")
    private fun folder(context: Context) = File(context.filesDir, "crash-reports")

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        started = SystemClock.elapsedRealtime()
        // Do not depend on a coroutine, UI thread, network, or a service during a crash.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (crashing.compareAndSet(false, true)) {
                try {
                    report(context, "uncaught", System.currentTimeMillis()) { out ->
                        out.println("Thread: ${thread.name} (${thread.id})")
                        error.printStackTrace(out)
                        if (error !is OutOfMemoryError) {
                            out.println("\nOther threads:")
                            try {
                                Thread.getAllStackTraces().entries.take(40).forEach { (other, stack) ->
                                    if (other !== thread) {
                                        out.println("\n${other.name} (${other.state})")
                                        stack.take(48).forEach { out.println("  at $it") }
                                    }
                                }
                            } catch (_: Throwable) { out.println("Other thread stacks unavailable") }
                        }
                    }
                } catch (_: Throwable) { /* Recording must never replace the original exception. */ }
            }
            try { previous?.uncaughtException(thread, error) }
            finally {
                Process.killProcess(Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun screen(name: String) {
        if (screen != name) { screen = name; breadcrumb("Screen: $name") }
    }

    fun breadcrumb(value: String) {
        synchronized(crumbs) {
            if (crumbs.size >= 30) crumbs.removeFirst()
            crumbs.addLast("${SystemClock.elapsedRealtime() - started}ms $value")
        }
    }

    private fun report(context: Context, kind: String, time: Long, write: (PrintWriter) -> Unit): File {
        val directory = folder(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date(time))
        val target = File(directory, "$stamp-$kind-${Process.myPid()}.txt")
        val temporary = File(directory, "${target.name}.tmp")
        try {
            temporary.outputStream().use { stream ->
                val writer = PrintWriter(BoundedRedactingWriter(stream.writer(Charsets.UTF_8), maxBytes / 4))
                writer.println("RemoteTool diagnostic report")
                writer.println("Kind: $kind")
                writer.println("Time: ${Date(time)}; epoch_ms=$time")
                writer.println("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); ${BuildConfig.BUILD_TYPE}")
                writer.println("Android: ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT}")
                writer.println("Device: ${Build.MANUFACTURER} ${Build.MODEL}; ABI ${Build.SUPPORTED_ABIS.joinToString()}")
                writer.println("Process: ${Process.myPid()}; uptime_ms=${SystemClock.elapsedRealtime() - started}")
                writer.println("Page: $screen")
                val heap = Runtime.getRuntime()
                writer.println("Heap: free=${heap.freeMemory()}, total=${heap.totalMemory()}, max=${heap.maxMemory()}; native=${Debug.getNativeHeapAllocatedSize()}")
                writer.println("\nRecent activity:")
                synchronized(crumbs) { crumbs.forEach(writer::println) }
                writer.println()
                write(writer)
                writer.flush()
                if (writer.checkError()) throw java.io.IOException("Cannot write crash report")
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) throw java.io.IOException("Cannot finalize crash report")
            files(context).drop(maxReports).forEach { it.delete() }
            return target
        } finally { temporary.delete() }
    }

    fun files(context: Context): List<File> = folder(context).listFiles()
        ?.filter { it.isFile && it.extension == "txt" }?.sortedByDescending { it.name }.orEmpty()

    fun read(file: File): String = file.reader(Charsets.UTF_8).use { reader ->
        val preview = StringBuilder()
        val buffer = CharArray(4096)
        while (preview.length < 64_000) {
            val count = reader.read(buffer, 0, minOf(buffer.size, 64_000 - preview.length))
            if (count < 0) break
            preview.append(buffer, 0, count)
        }
        preview.toString() + if (reader.read() >= 0) "\n…预览已截断，导出可查看完整日志。" else ""
    }

    fun clear(context: Context) {
        files(context).forEach { if (!it.delete()) throw java.io.IOException("日志删除失败") }
        File(context.cacheDir, "crash-export").listFiles()?.forEach { it.delete() }
    }

    fun export(context: Context): File {
        val reports = files(context)
        require(reports.isNotEmpty()) { "暂无日志" }
        val directory = File(context.cacheDir, "crash-export").apply { mkdirs() }
        directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
        val zip = File(directory, "RemoteTool-diagnostics-${System.currentTimeMillis()}.zip")
        try {
            ZipOutputStream(zip.outputStream()).use { output ->
                reports.forEach { file ->
                    output.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
            return zip
        } catch (error: Exception) { zip.delete(); throw error }
    }

    fun collectSystemExits(context: Context) {
        Thread({
            try {
                files(context).drop(maxReports).forEach { it.delete() }
                folder(context).listFiles()?.filter { it.extension == "tmp" && it.lastModified() < System.currentTimeMillis() - 86_400_000L }?.forEach { it.delete() }
                if (Build.VERSION.SDK_INT < 30) return@Thread
                val manager = context.getSystemService(ActivityManager::class.java)
                val preferences = context.getSharedPreferences("crash-diagnostics", Context.MODE_PRIVATE)
                val last = preferences.getLong("last_system_exit", 0L)
                val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, maxReports).sortedBy { it.timestamp }
                for (exit in exits.filter { it.timestamp > last }) {
                    if (exit.reason in setOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE,
                            ApplicationExitInfo.REASON_ANR, ApplicationExitInfo.REASON_LOW_MEMORY,
                            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_SIGNALED)) {
                        report(context, "system-${exit.reason}", exit.timestamp) { out ->
                            out.println("Historical process exit (device/app fields above describe this launch):")
                            out.println("PID: ${exit.pid}; process: ${exit.processName}")
                            out.println("Reason: ${exit.reason}; status: ${exit.status}; importance: ${exit.importance}")
                            out.println("Description: ${exit.description}")
                            out.println("PSS: ${exit.pss} KiB; RSS: ${exit.rss} KiB")
                            try {
                                exit.traceInputStream?.use { stream ->
                                    if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                                        out.println("\nNative tombstone protobuf (base64, up to 128 KiB):")
                                        val bytes = java.io.ByteArrayOutputStream()
                                        val buffer = ByteArray(4096)
                                        while (bytes.size() < 128 * 1024) {
                                            val count = stream.read(buffer, 0, minOf(buffer.size, 128 * 1024 - bytes.size()))
                                            if (count < 0) break
                                            bytes.write(buffer, 0, count)
                                        }
                                        out.println(android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.DEFAULT))
                                        return@use
                                    }
                                    val reader = stream.bufferedReader()
                                    out.println("\nSystem trace:")
                                    var remaining = 180_000
                                    val buffer = CharArray(4096)
                                    while (remaining > 0) {
                                        val count = reader.read(buffer, 0, minOf(buffer.size, remaining))
                                        if (count < 0) break
                                        out.write(buffer, 0, count); remaining -= count
                                    }
                                }
                            } catch (error: Exception) { out.println("Trace unavailable: ${error.javaClass.simpleName}") }
                        }
                    }
                    preferences.edit().putLong("last_system_exit", exit.timestamp).commit()
                }
            } catch (_: Throwable) { /* Historical evidence is best effort and cannot block startup. */ }
        }, "crash-history").start()
    }

    /** Redact per line, cap both a long line and the whole report, and stream to disk. */
    private class BoundedRedactingWriter(private val output: Writer, private var remaining: Int) : Writer() {
        private val line = StringBuilder()
        override fun write(buffer: CharArray, offset: Int, count: Int) {
            for (i in offset until offset + count) {
                if (remaining <= 0) break
                val char = buffer[i]
                if (char == '\n') flushLine() else if (line.length < 8192) line.append(char)
            }
        }
        private fun flushLine() {
            if (remaining <= 0) { line.setLength(0); return }
            val safe = secret.replace(cookies.replace(line, "$1[REDACTED]"), "$1[REDACTED]").take(remaining)
            output.write(safe); output.write("\n")
            remaining -= safe.length + 1
            line.setLength(0)
        }
        override fun flush() { if (line.isNotEmpty()) flushLine(); output.flush() }
        override fun close() { flush(); output.close() }
    }
}
