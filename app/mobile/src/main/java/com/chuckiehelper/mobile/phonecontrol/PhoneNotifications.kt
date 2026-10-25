package com.chuckiehelper.mobile.phonecontrol

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import org.json.JSONArray
import org.json.JSONObject

class PhoneNotifications : NotificationListenerService() {
    override fun onListenerConnected() { current = this }
    override fun onListenerDisconnected() { if (current === this) current = null }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }
    fun execute(a: JSONObject): JSONObject = when (a.getString("action")) {
        "list" -> JSONObject().put("notifications", JSONArray(activeNotifications.take(150).map {
            JSONObject().put("key", it.key).put("package", it.packageName).put("time", it.postTime)
                .put("title", it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
                .put("text", it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                .put("actions", JSONArray(it.notification.actions.orEmpty().mapIndexed { i, action -> JSONObject().put("index", i).put("title", action.title.toString()).put("reply", !action.remoteInputs.isNullOrEmpty()) }))
        }))
        "dismiss" -> { cancelNotification(a.getString("key")); JSONObject().put("accepted", true) }
        "open", "action", "reply" -> {
            val n = activeNotifications.firstOrNull { it.key == a.getString("key") }?.notification ?: fail("not_found", "通知已消失")
            if (a.getString("action") == "open") (n.contentIntent ?: fail("not_supported", "通知没有打开操作")).send()
            else {
                val action = n.actions?.getOrNull(a.optInt("index", 0)) ?: fail("not_found", "通知操作不存在")
                val intent = Intent()
                if (a.getString("action") == "reply") {
                    val inputs = action.remoteInputs ?: fail("not_supported", "操作不支持回复")
                    RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, a.getString("text")) } })
                }
                action.actionIntent.send(this, 0, intent)
            }; JSONObject().put("accepted", true).put("verified", false)
        }
        else -> fail("invalid_arguments", "action: list/dismiss/open/action/reply")
    }
    companion object { @Volatile var current: PhoneNotifications? = null; private set }
}
