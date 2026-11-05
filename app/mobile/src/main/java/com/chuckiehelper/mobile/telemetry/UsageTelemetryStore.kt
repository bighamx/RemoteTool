package com.chuckiehelper.mobile.telemetry

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

internal const val USAGE_STATS_KEY = "usage_statistics_enabled"
internal const val USAGE_STATS_ENDPOINT = "https://api.xbyham.com/api/app-telemetry/v1/report"

/** Accessed only by the telemetry actor. Snapshots are cumulative, never deltas. */
internal class UsageTelemetryStore(private val prefs: SharedPreferences) {
    private fun state() = runCatching { JSONObject(prefs.getString("state", "{}")!!) }.getOrDefault(JSONObject())
    private fun save(state: JSONObject) {
        val json = state.toString()
        if (prefs.getString("state", null) != json) check(prefs.edit().putString("state", json).commit())
    }

    fun record(now: Long, countOpen: Boolean) {
        val state = state()
        if (state.optString("installationId").isEmpty()) {
            state.put("installationId", UUID.randomUUID().toString())
            state.put("firstSeenUtc", Instant.ofEpochMilli(now).toString())
        }
        val today = Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Hong_Kong")).toLocalDate()
        val day = today.toString()
        val days = state.optJSONObject("days") ?: JSONObject().also { state.put("days", it) }
        val ack = state.optJSONObject("ack") ?: JSONObject().also { state.put("ack", it) }
        days.keys().asSequence().toList().forEach {
            if (it < today.minusDays(30).toString() || it > day) { days.remove(it); ack.remove(it) }
        }
        val opens = days.optInt(day)
        days.put(day, if (countOpen) (opens + 1).coerceAtMost(10_000) else opens)
        save(state)
    }

    fun pending(): JSONObject? {
        val state = state()
        if (state.optString("installationId").isEmpty()) return null
        val days = state.optJSONObject("days") ?: JSONObject()
        val ack = state.optJSONObject("ack") ?: JSONObject()
        val pending = days.keys().asSequence().filter { !ack.has(it) || days.optInt(it) > ack.optInt(it) }.sorted().toList()
        if (state.optBoolean("installationAcknowledged") && pending.isEmpty()) return null
        return JSONObject().put("installationId", state.getString("installationId"))
            .put("firstSeenUtc", state.getString("firstSeenUtc"))
            .put("days", JSONArray(pending.map { JSONObject().put("date", it).put("openCount", days.optInt(it)) }))
    }

    fun acknowledge(snapshot: JSONObject) {
        val state = state()
        if (state.optString("installationId") != snapshot.optString("installationId")) return
        val ack = state.optJSONObject("ack") ?: JSONObject().also { state.put("ack", it) }
        val days = state.optJSONObject("days") ?: JSONObject()
        snapshot.getJSONArray("days").let { entries -> (0 until entries.length()).forEach { i ->
            val row = entries.getJSONObject(i)
            val date = row.getString("date")
            if (days.has(date)) ack.put(date, maxOf(ack.optInt(date), row.getInt("openCount")))
        } }
        state.put("installationAcknowledged", true)
        save(state)
    }

    fun discardPending() {
        val state = state()
        // Keep the random installation ID so toggling cannot create new installs.
        // Retain only already-acknowledged counters as the baseline for MAX
        // snapshots after opt-in resumes; discard every unsent increment.
        state.put("days", JSONObject((state.optJSONObject("ack") ?: JSONObject()).toString()))
        save(state)
    }
}
