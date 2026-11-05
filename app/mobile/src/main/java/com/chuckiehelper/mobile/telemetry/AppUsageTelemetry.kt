package com.chuckiehelper.mobile.telemetry

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.chuckiehelper.mobile.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** App-only usage counts. Never accesses the selected server, chats or accounts. */
internal class AppUsageTelemetry internal constructor(
    private val app: Application,
    private val endpoint: String = USAGE_STATS_ENDPOINT,
) : Application.ActivityLifecycleCallbacks {
    private val settings = app.getSharedPreferences("MainActivity", Context.MODE_PRIVATE)
    private val store = UsageTelemetryStore(app.getSharedPreferences("usage-telemetry", Context.MODE_PRIVATE))
    // One actor serializes disk changes, consent changes and acknowledgements.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private val client = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build()
    @Volatile private var enabled = settings.getBoolean(USAGE_STATS_KEY, true)
    @Volatile private var generation = 0L
    @Volatile private var call: Call? = null
    private var started = 0
    private var lastBackground: Long? = null
    private var observedForeground = false
    private var heartbeat: Job? = null
    private var retries: Job? = null
    private var nextAttemptAt = 0L
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == USAGE_STATS_KEY) {
            val next = prefs.getBoolean(USAGE_STATS_KEY, true)
            if (next != enabled) {
                enabled = next
                generation++
                call?.cancel()
                scope.launch {
                    retries?.cancel()
                    nextAttemptAt = 0L
                    if (!next) runCatching { store.discardPending() }
                    else { runCatching { store.record(System.currentTimeMillis(), false) }; signals.trySend(Unit) }
                }
            }
        }
    }

    init {
        settings.registerOnSharedPreferenceChangeListener(listener)
        app.registerActivityLifecycleCallbacks(this)
        scope.launch {
            var delayMs = 30_000L
            for (ignored in signals) {
                if (!enabled) continue
                val snapshot = runCatching { store.pending() }.getOrNull() ?: continue
                val remaining = nextAttemptAt - SystemClock.elapsedRealtime()
                if (remaining > 0) {
                    if (retries?.isActive != true) retries = scope.launch { delay(remaining); signals.trySend(Unit) }
                    continue
                }
                val epoch = generation
                val body = snapshot.toString()
                val payload = JSONObject(body).put("schemaVersion", 1)
                    .put("appId", if (BuildConfig.PHONE_COMPANION) "remotetool-phonecontrol" else "remotetool")
                    .put("packageName", BuildConfig.APPLICATION_ID)
                    .put("appVersion", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE)
                    .put("device", JSONObject().put("manufacturer", metadata(Build.MANUFACTURER, 100))
                        .put("brand", metadata(Build.BRAND, 100)).put("model", metadata(Build.MODEL, 100))
                        .put("androidVersion", metadata(Build.VERSION.RELEASE, 32)).put("sdkInt", Build.VERSION.SDK_INT))
                val success = runCatching {
                    val request = Request.Builder().url(endpoint)
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                    val outgoing = client.newCall(request)
                    call = outgoing
                    if (!enabled || epoch != generation) { outgoing.cancel(); false }
                    else withContext(Dispatchers.IO) {
                        outgoing.execute().use { response ->
                            response.code in listOf(200, 202) && response.peekBody(4096).string().let {
                                JSONObject(it).opt("accepted") == true
                            } == true
                        }
                    }
                }.getOrDefault(false)
                call = null
                if (!enabled || epoch != generation) continue
                if (success && runCatching { store.acknowledge(snapshot) }.isSuccess) {
                    delayMs = 30_000L
                    nextAttemptAt = 0L
                    retries?.cancel()
                    if (runCatching { store.pending() }.getOrNull() != null) signals.trySend(Unit)
                } else {
                    retries?.cancel()
                    val retryDelay = delayMs
                    nextAttemptAt = SystemClock.elapsedRealtime() + retryDelay
                    retries = scope.launch { delay(retryDelay); signals.trySend(Unit) }
                    delayMs = (delayMs * 2).coerceAtMost(6 * 60 * 60 * 1000L)
                }
            }
        }
    }

    private fun record(open: Boolean) {
        val epoch = generation
        scope.launch {
            if (enabled && epoch == generation) {
                runCatching { store.record(System.currentTimeMillis(), open) }
                signals.trySend(Unit)
            }
        }
    }

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) {
            val newOpen = !observedForeground || lastBackground?.let { SystemClock.elapsedRealtime() - it >= 30_000L } == true
            observedForeground = true
            lastBackground = null
            record(newOpen)
            heartbeat?.cancel()
            heartbeat = scope.launch {
                while (isActive) { delay(60_000L); record(false) }
            }
        }
    }
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) {
            if (!activity.isChangingConfigurations) lastBackground = SystemClock.elapsedRealtime()
            heartbeat?.cancel()
        }
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    internal fun close() {
        settings.unregisterOnSharedPreferenceChangeListener(listener)
        app.unregisterActivityLifecycleCallbacks(this)
        enabled = false
        generation++
        call?.cancel()
        scope.cancel()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }

    companion object {
        private fun metadata(value: String, length: Int) = value.map { if (it.isISOControl()) ' ' else it }
            .joinToString("").trim().ifEmpty { "unknown" }.take(length)
        private var instance: AppUsageTelemetry? = null
        @Synchronized fun install(app: Application) {
            if (!java.lang.Boolean.getBoolean("remotetool.telemetry.disabled") && instance == null) instance = AppUsageTelemetry(app)
        }
    }
}
