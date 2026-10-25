package com.chuckiehelper.mobile.phonecontrol

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.Cursor
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Native Android operations, with system permissions enforced by Android at the call site. */
class PhoneAndroidActions(private val context: Context) {
    fun execute(tool: String, a: JSONObject): JSONObject = when (tool) {
        "phone_android" -> android(a)
        "phone_intent" -> intent(a)
        "phone_content" -> content(a)
        "phone_files" -> files(a)
        "phone_notifications" -> (PhoneNotifications.current ?: fail("permission_required", "请授予通知读取权限")).execute(a)
        else -> fail("unknown_tool", tool)
    }
    private fun android(a: JSONObject): JSONObject {
        val p = a.optJSONObject("parameters") ?: JSONObject()
        val audio = context.getSystemService(AudioManager::class.java)
        return when (a.getString("action")) {
            "device" -> {
                val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                JSONObject().put("model", Build.MODEL).put("manufacturer", Build.MANUFACTURER).put("api", Build.VERSION.SDK_INT)
                    .put("battery_level", battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)).put("battery_scale", battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
                    .put("charging", battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0).put("interactive", context.getSystemService(PowerManager::class.java).isInteractive)
                    .put("locked", context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
            }
            "permissions" -> {
                val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
                JSONObject().put("permissions", JSONArray(permissions.map { JSONObject().put("name", it).put("granted", context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED) }))
                    .put("all_files", Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()).put("overlay", Settings.canDrawOverlays(context))
                    .put("write_settings", Settings.System.canWrite(context)).put("notifications", PhoneNotifications.current != null)
            }
            "clipboard_get" -> onMain {
                val clipboard = context.getSystemService(ClipboardManager::class.java)
                JSONObject().put("text", clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()).put("note", "系统可能限制后台读取")
            }
            "clipboard_set" -> onMain { context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("RemoteTool", p.getString("text"))); accepted() }
            "volume_get" -> JSONObject().put("music", audio.getStreamVolume(AudioManager.STREAM_MUSIC)).put("music_max", audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).put("ringer_mode", audio.ringerMode)
            "volume_set" -> { val stream = p.optInt("stream", AudioManager.STREAM_MUSIC); audio.setStreamVolume(stream, p.getInt("value").coerceIn(0, audio.getStreamMaxVolume(stream)), 0); accepted() }
            "ringer_set" -> { audio.ringerMode = p.getInt("value"); accepted() }
            "brightness_set" -> { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, p.getInt("value").coerceIn(0, 255)); accepted() }
            "flashlight" -> { val camera = context.getSystemService(CameraManager::class.java); camera.setTorchMode(p.optString("camera_id").ifBlank { camera.cameraIdList.first() }, p.getBoolean("enabled")); accepted() }
            "vibrate" -> { @Suppress("DEPRECATION") val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createOneShot(p.optLong("duration_ms", 300).coerceIn(1, 5000), VibrationEffect.DEFAULT_AMPLITUDE)); accepted() }
            "location" -> {
                val manager = context.getSystemService(LocationManager::class.java)
                @Suppress("MissingPermission") val locations = manager.getProviders(true).mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
                val location = locations.maxByOrNull { it.time } ?: fail("not_found", "暂无已授权位置缓存")
                JSONObject().put("latitude", location.latitude).put("longitude", location.longitude).put("accuracy", location.accuracy).put("time", location.time).put("cached", true)
            }
            "sensors" -> JSONObject().put("sensors", JSONArray(context.getSystemService(SensorManager::class.java).getSensorList(Sensor.TYPE_ALL).map {
                JSONObject().put("name", it.name).put("type", it.type).put("vendor", it.vendor).put("max_range", it.maximumRange)
            }))
            "network" -> {
                val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
                val network = manager.activeNetwork; val caps = manager.getNetworkCapabilities(network)
                JSONObject().put("connected", caps != null).put("capabilities", caps?.toString()).put("link", manager.getLinkProperties(network)?.toString())
            }
            "bluetooth" -> {
                val adapter = context.getSystemService(android.bluetooth.BluetoothManager::class.java).adapter
                @Suppress("MissingPermission") val devices = adapter?.bondedDevices?.map { JSONObject().put("name", it.name).put("address", it.address) }.orEmpty()
                JSONObject().put("enabled", adapter?.isEnabled == true).put("bonded", JSONArray(devices))
            }
            "sms_send" -> {
                @Suppress("DEPRECATION") val sms = android.telephony.SmsManager.getDefault()
                val parts = sms.divideMessage(p.getString("text"))
                sms.sendMultipartTextMessage(p.getString("number"), null, parts, null, null)
                accepted().put("note", "仅表示已提交，未确认送达")
            }
            "wake" -> { context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "RemoteTool:phone-wake").acquire(3000); accepted() }
            else -> fail("invalid_arguments", "Unsupported Android action")
        }
    }
    private fun intent(a: JSONObject): JSONObject {
        val mode = a.optString("mode", "activity")
        val intent = Intent(a.getString("action")).apply {
            a.optString("uri").takeIf { it.isNotBlank() }?.let { data = Uri.parse(it) }
            a.optString("mime_type").takeIf { it.isNotBlank() }?.let { setDataAndType(data, it) }
            a.optString("package_name").takeIf { it.isNotBlank() }?.let { setPackage(it) }
            a.optString("component").takeIf { it.isNotBlank() }?.let { component = ComponentName.unflattenFromString(it) ?: fail("invalid_arguments", "Invalid component") }
            val extras = a.optJSONObject("extras") ?: JSONObject()
            for (key in extras.keys()) when (val v = extras.get(key)) {
                is String -> putExtra(key, v); is Boolean -> putExtra(key, v); is Int -> putExtra(key, v); is Long -> putExtra(key, v); is Double -> putExtra(key, v)
                else -> fail("invalid_arguments", "Only primitive Intent extras supported")
            }
            addFlags(a.optInt("flags", 0))
        }
        when (mode) {
            "activity" -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "broadcast" -> context.sendBroadcast(intent)
            "service" -> context.startService(intent)
            else -> fail("invalid_arguments", "mode must be activity/broadcast/service")
        }; return accepted()
    }
    private fun content(a: JSONObject): JSONObject {
        val uri = Uri.parse(a.getString("uri")); if (uri.scheme != "content") fail("invalid_arguments", "content:// URI required")
        val resolver = context.contentResolver
        val args = a.optJSONArray("selection_args")?.let { array -> Array(array.length()) { array.getString(it) } }
        val selection = a.optString("selection").takeIf { it.isNotBlank() }
        val values = ContentValues().apply {
            val data = a.optJSONObject("values") ?: JSONObject()
            for (key in data.keys()) when (val value = data.get(key)) {
                JSONObject.NULL -> putNull(key); is String -> put(key, value); is Boolean -> put(key, value); is Int -> put(key, value); is Long -> put(key, value); is Double -> put(key, value)
                else -> fail("invalid_arguments", "Unsupported content value")
            }
        }
        return when (a.getString("action")) {
            "query" -> {
                val projection = a.optJSONArray("projection")?.let { array -> Array(array.length()) { array.getString(it) } }
                val rows = JSONArray(); val max = a.optInt("limit", 100).coerceIn(1, 500)
                resolver.query(uri, projection, selection, args, a.optString("sort").takeIf { it.isNotBlank() })?.use { c ->
                    while (rows.length() < max && c.moveToNext()) { val row = JSONObject()
                        for (i in 0 until c.columnCount) row.put(c.getColumnName(i), when (c.getType(i)) {
                            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL; Cursor.FIELD_TYPE_INTEGER -> c.getLong(i); Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_BLOB -> "[binary]"; else -> c.getString(i)?.take(16000)
                        }); rows.put(row)
                    }
                }; JSONObject().put("rows", rows)
            }
            "insert" -> JSONObject().put("uri", resolver.insert(uri, values)?.toString())
            "update", "delete" -> {
                if (!a.optBoolean("allow_all") && selection == null && uri.lastPathSegment?.toLongOrNull() == null) fail("invalid_arguments", "Provide an item URI/selection or allow_all=true")
                JSONObject().put("affected", if (a.getString("action") == "update") resolver.update(uri, values, selection, args) else resolver.delete(uri, selection, args))
            }
            else -> fail("invalid_arguments", "action must be query/insert/update/delete")
        }
    }
    private fun files(a: JSONObject): JSONObject {
        val path = a.getString("path"); val action = a.getString("action")
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            return when (action) {
                "read" -> context.contentResolver.openInputStream(uri)?.use { val bytes = boundedRead(it); JSONObject().put("data_base64", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)) } ?: fail("not_found", path)
                "write" -> { val bytes = decode(a); context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: fail("not_found", path); JSONObject().put("bytes", bytes.size) }
                else -> fail("invalid_arguments", "content URI supports read/write")
            }
        }
        val file = File(path); if (!file.isAbsolute) fail("invalid_arguments", "Absolute Android path required")
        return when (action) {
            "list" -> JSONObject().put("files", JSONArray((file.listFiles() ?: fail("not_found", "目录不可访问")).take(500).map { JSONObject().put("name", it.name).put("path", it.absolutePath).put("directory", it.isDirectory).put("size", it.length()) }))
            "stat" -> JSONObject().put("exists", file.exists()).put("directory", file.isDirectory).put("size", file.length()).put("modified", file.lastModified()).put("readable", file.canRead()).put("writable", file.canWrite())
            "read" -> { if (file.length() > 2 * 1024 * 1024) fail("invalid_arguments", "File exceeds 2 MiB"); JSONObject().put("data_base64", android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)) }
            "write" -> { val bytes = decode(a); java.io.FileOutputStream(file, a.optBoolean("append")).use { it.write(bytes) }; JSONObject().put("bytes", bytes.size) }
            "mkdir" -> JSONObject().put("created", file.mkdirs())
            "delete" -> JSONObject().put("deleted", file.delete())
            "move" -> JSONObject().put("moved", file.renameTo(File(a.getString("destination"))))
            "copy" -> { file.copyTo(File(a.getString("destination")), a.optBoolean("overwrite")); accepted() }
            else -> fail("invalid_arguments", "action must be list/stat/read/write/mkdir/delete/move/copy")
        }
    }
    private fun decode(a: JSONObject): ByteArray { val text = a.getString("data_base64"); if (text.length > 2_800_000) fail("invalid_arguments", "File exceeds 2 MiB"); return android.util.Base64.decode(text, android.util.Base64.DEFAULT).also { if (it.size > 2 * 1024 * 1024) fail("invalid_arguments", "File exceeds 2 MiB") } }
    private fun boundedRead(input: java.io.InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val n = input.read(buffer); if (n < 0) break; if (output.size() + n > 2 * 1024 * 1024) fail("invalid_arguments", "File exceeds 2 MiB"); output.write(buffer, 0, n) }; return output.toByteArray()
    }
    private fun accepted() = JSONObject().put("accepted", true).put("verified", false)
    private fun onMain(action: () -> JSONObject): JSONObject {
        val done = java.util.concurrent.CountDownLatch(1); var result: JSONObject? = null; var error: Exception? = null
        Handler(Looper.getMainLooper()).post { try { result = action() } catch (e: Exception) { error = e } finally { done.countDown() } }
        if (!done.await(5, java.util.concurrent.TimeUnit.SECONDS)) fail("delivery_unknown", "Android 主线程回执超时")
        error?.let { throw it }; return result!!
    }
}
