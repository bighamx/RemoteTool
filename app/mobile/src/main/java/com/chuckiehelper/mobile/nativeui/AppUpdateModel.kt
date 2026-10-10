package com.chuckiehelper.mobile.nativeui

import android.app.Activity
import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chuckiehelper.mobile.BuildConfig
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal enum class UpdatePhase { Idle, Checking, Available, Downloading, Ready }

/** Independent of device sessions: no computer credentials or GitHub tokens leave the app. */
internal class AppUpdateModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("app_updates", 0)
    private val http = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    private val apk get() = File(getApplication<Application>().filesDir, "updates/update.apk")
    private var job: Job? = null
    private var awaitingInstallPermission = false
    var phase by mutableStateOf(UpdatePhase.Idle); private set
    var release by mutableStateOf<AppRelease?>(null); private set
    var downloaded by mutableLongStateOf(0L); private set
    var error by mutableStateOf<String?>(null); private set
    var message by mutableStateOf("启动和回到前台时自动检查更新"); private set
    var panelVisible by mutableStateOf(false)
    var automatic by mutableStateOf(prefs.getBoolean("automatic", true)); private set
    var wifiOnly by mutableStateOf(prefs.getBoolean("wifi_only", true)); private set
    var checkedAt by mutableLongStateOf(prefs.getLong("checked_at", 0)); private set
    var busy by mutableStateOf(false); private set
    val subtitle get() = when (phase) {
        UpdatePhase.Downloading -> "正在下载 ${release?.versionName.orEmpty()}"
        UpdatePhase.Ready -> "${release?.versionName.orEmpty()} 已下载，点击安装"
        UpdatePhase.Available -> "发现新版本 ${release?.versionName.orEmpty()}"
        UpdatePhase.Checking -> "正在检查更新"
        else -> "当前 v${BuildConfig.VERSION_NAME} · 自动更新${if (automatic) "已开启" else "已关闭"}"
    }

    init {
        work {
            val cached = runCatching { restoreAppRelease(JSONObject(prefs.getString("cached_release", "").orEmpty())) }.getOrNull()
            if (cached != null && cached.versionCode > BuildConfig.VERSION_CODE && apk.isFile) {
                val valid = try { withContext(Dispatchers.IO) { validateApk(apk, cached) }; true }
                    catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
                if (valid) { release = cached; phase = UpdatePhase.Ready; downloaded = cached.size }
                else clearCached()
            } else clearCached()
            // A process killed during download leaves only a partial file, never an installable one.
            withContext(Dispatchers.IO) { File(apk.parentFile, "update.apk.part").delete() }
        }
    }

    private fun work(action: suspend () -> Unit) {
        busy = true
        job = viewModelScope.launch { try { action() } finally { busy = false } }
    }

    fun updateAutomatic(value: Boolean) {
        automatic = value; prefs.edit().putBoolean("automatic", value).apply()
    }
    fun updateWifiOnly(value: Boolean) {
        wifiOnly = value; prefs.edit().putBoolean("wifi_only", value).apply()
    }
    fun open() { panelVisible = true; check(manual = true) }
    fun later() {
        release?.let { prefs.edit().putLong("ignored_version", it.versionCode).apply() }
        panelVisible = false
    }
    fun onForeground(activity: Activity?) {
        if (awaitingInstallPermission && activity != null) {
            awaitingInstallPermission = false
            if (activity.packageManager.canRequestPackageInstalls()) install(activity)
            else { panelVisible = true; error = "尚未允许安装更新，可点击安装重新授权" }
        }
        if (phase == UpdatePhase.Ready && release?.versionCode?.let { prefs.getLong("ignored_version", 0) < it } == true)
            panelVisible = true
        if (!busy && automatic) check(manual = false)
    }

    fun check(manual: Boolean) {
        if (busy) return
        val now = System.currentTimeMillis()
        val elapsed = now - prefs.getLong("attempt_at", 0)
        if (!manual && prefs.getInt("attempt_version", 0) == BuildConfig.VERSION_CODE &&
            elapsed in 0 until TimeUnit.HOURS.toMillis(6)) return
        prefs.edit().putLong("attempt_at", now).putInt("attempt_version", BuildConfig.VERSION_CODE).apply()
        val previous = phase
        work {
            phase = UpdatePhase.Checking; error = null
            try {
                val latest = withTimeout(45_000) { withContext(Dispatchers.IO) { findLatest() } }
                checkedAt = System.currentTimeMillis()
                prefs.edit().putLong("checked_at", checkedAt).apply()
                if (latest == null || latest.versionCode <= BuildConfig.VERSION_CODE) {
                    message = if (latest == null) "尚未发布新版 Android 更新包" else "已是最新版本"
                    if (previous == UpdatePhase.Ready && release?.versionCode?.let { it > BuildConfig.VERSION_CODE } == true) {
                        phase = UpdatePhase.Ready
                    } else { release = null; phase = UpdatePhase.Idle }
                    return@work
                }
                val alreadyReady = previous == UpdatePhase.Ready && release?.sha256 == latest.sha256 && apk.isFile
                release = latest
                phase = if (alreadyReady) UpdatePhase.Ready else UpdatePhase.Available
                message = "发现新版本 v${latest.versionName}"
                val ignored = prefs.getLong("ignored_version", 0) >= latest.versionCode
                if (manual || !ignored) panelVisible = true
                if (!alreadyReady && !manual && automatic && !ignored && (!wifiOnly || isUnmetered())) downloadNow(latest)
            } catch (timeout: TimeoutCancellationException) {
                phase = if (previous == UpdatePhase.Ready) previous else if (release != null) UpdatePhase.Available else UpdatePhase.Idle
                if (manual || panelVisible) error = "检查更新超时，请稍后重试"
            } catch (cancel: CancellationException) {
                phase = if (previous == UpdatePhase.Ready) previous else if (release != null) UpdatePhase.Available else UpdatePhase.Idle
                throw cancel
            } catch (failure: Exception) {
                phase = if (previous == UpdatePhase.Ready) previous else if (release != null) UpdatePhase.Available else UpdatePhase.Idle
                currentCoroutineContext().ensureActive()
                if (manual || panelVisible) error = "检查更新失败：${failure.message ?: "网络不可用"}"
            }
        }
    }

    fun download() {
        val selected = release ?: return
        if (busy) return
        error = null
        work { downloadNow(selected) }
    }

    fun cancelDownload() {
        if (phase == UpdatePhase.Downloading) job?.cancel()
    }

    private suspend fun downloadNow(selected: AppRelease) {
        phase = UpdatePhase.Downloading; downloaded = 0; error = null
        val partial = File(apk.parentFile, "update.apk.part")
        try {
            withContext(Dispatchers.IO) {
                val parent = apk.parentFile ?: throw IOException("无法创建更新目录")
                if (!parent.isDirectory && !parent.mkdirs()) throw IOException("无法创建更新目录")
                val digest = MessageDigest.getInstance("SHA-256")
                var read = 0L
                var lastUi = 0L
                fetch(selected.apkUrl) { response ->
                    val body = response.body ?: throw IOException("更新包为空")
                    if (body.contentLength() > 0 && body.contentLength() != selected.size) throw IOException("更新包大小与清单不一致")
                    body.byteStream().use { input -> partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            read += count
                            if (read > selected.size) throw IOException("更新包大小超出清单")
                            output.write(buffer, 0, count); digest.update(buffer, 0, count)
                            val now = android.os.SystemClock.elapsedRealtime()
                            if (now - lastUi >= 250) {
                                lastUi = now
                                withContext(Dispatchers.Main) { downloaded = read }
                            }
                        }
                    } }
                }
                if (read != selected.size) throw IOException("文件未完整下载")
                if (digest.digest().hex() != selected.sha256) throw IOException("SHA-256 校验失败")
                validateApk(partial, selected, checkHash = false)
                currentCoroutineContext().ensureActive()
                Files.move(partial.toPath(), apk.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            prefs.edit().putString("cached_release", selected.json().toString()).apply()
            downloaded = selected.size; phase = UpdatePhase.Ready
        } catch (cancel: CancellationException) {
            phase = UpdatePhase.Available
            throw cancel
        } catch (failure: Exception) {
            phase = UpdatePhase.Available
            currentCoroutineContext().ensureActive()
            error = "下载更新失败：${failure.message ?: "网络不可用"}"
        } finally { withContext(NonCancellable + Dispatchers.IO) { partial.delete() } }
    }

    fun install(activity: Activity) {
        val selected = release ?: return
        if (busy || phase != UpdatePhase.Ready) return
        error = null
        work {
            try {
                withContext(Dispatchers.IO) { validateApk(apk, selected) }
                if (!activity.packageManager.canRequestPackageInstalls()) {
                    awaitingInstallPermission = true
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                } else {
                    val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", apk)
                    activity.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("应用更新", uri) })
                    panelVisible = false
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = "无法安装更新：${failure.message ?: "请稍后重试"}" }
        }
    }

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, selected: AppRelease, checkHash: Boolean = true) {
        require(file.isFile && file.length() == selected.size) { "更新文件不完整，请重新下载" }
        if (checkHash) {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = input.read(buffer); if (n < 0) break; sha.update(buffer, 0, n) }
            }
            require(sha.digest().hex() == selected.sha256) { "更新文件校验失败，请重新下载" }
        }
        val app = getApplication<Application>()
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("无法读取安装包")
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        fun code(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        fun signers(info: PackageInfo): Set<String> =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
                .orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        validateUpdateIdentity(archive.packageName, code(archive), signers(archive), code(installed), signers(installed), selected)
    }

    private suspend fun findLatest(): AppRelease? {
        // Prefer one public asset request; VPN/mobile users may share an exhausted
        // unauthenticated GitHub API quota despite checking only once.
        val latestManifest = try { fetchText(UPDATE_LATEST_MANIFEST_URL) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: IOException) { null }
        if (latestManifest != null) return parseLatestAppRelease(JSONObject(latestManifest))
        // A newer web-only release may not contain the Android manifest.
        val releases = JSONArray(fetchText(UPDATE_RELEASES_URL))
        return appReleaseCandidates(releases).map { candidate ->
            val assets = candidate.getJSONArray("assets")
            val manifest = (0 until assets.length()).map { assets.getJSONObject(it) }.first { it.optString("name") == UPDATE_MANIFEST }
            val url = manifest.getString("browser_download_url")
            require(isUpdateAssetUrl(url)) { "更新清单地址不属于本项目" }
            parseAppRelease(JSONObject(fetchText(url)), candidate)
        }.maxByOrNull { it.versionCode }
    }

    private suspend fun fetchText(url: String): String = fetch(url) { response ->
        val body = response.body ?: throw IOException("更新服务未返回数据")
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= 1024 * 1024) { "更新清单过大" }
                output.write(buffer, 0, n)
            }
            output.toString("UTF-8")
        }
    }

    private suspend fun <T> fetch(url: String, redirects: Int = 0, action: suspend (Response) -> T): T {
        require(isUpdateTransportUrl(url) && redirects <= 5) { "更新服务地址无效" }
        val call = http.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json, application/octet-stream")
            .header("User-Agent", "RemoteTool-Android/${BuildConfig.VERSION_NAME}").build())
        return withStreamingCallCancellation(call) {
            call.execute().use { response ->
                if (response.code in setOf(301, 302, 303, 307, 308)) {
                    val next = response.header("Location")?.let { response.request.url.resolve(it) } ?: throw IOException("更新下载地址缺失")
                    return@use fetch(next.toString(), redirects + 1, action)
                }
                if (!response.isSuccessful) throw IOException(updateHttpFailureMessage(response.code,
                    response.header("X-RateLimit-Remaining"), response.header("Retry-After")))
                action(response)
            }
        }
    }

    private fun isUnmetered(): Boolean {
        val manager = getApplication<Application>().getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork != null && !manager.isActiveNetworkMetered
    }
    private fun clearCached() {
        prefs.edit().remove("cached_release").apply()
        apk.delete()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
