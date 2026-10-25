package com.chuckiehelper.mobile.phonecontrol

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import org.json.JSONObject

internal data class HyperIslandStatus(val protocol: Int = 0, val allowed: Boolean = false) {
    fun json() = JSONObject().put("protocol", protocol).put("supported", protocol >= 2).put("allowed", allowed)
    val label get() = when {
        protocol >= 3 && allowed -> "系统开关已开启"
        protocol >= 3 -> "超级岛未授权"
        protocol == 2 && allowed -> "系统开关已开启"
        protocol == 2 -> "焦点通知未授权"
        else -> "当前系统不支持"
    }
}

/** Official client notification extras. Does not change Xiaomi permissions or impersonate an approved app. */
internal object HyperIslandSupport {
    fun query(context: Context): HyperIslandStatus {
        val protocol = runCatching { Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0) }.getOrDefault(0)
        if (protocol < 2) return HyperIslandStatus(protocol)
        val allowed = runCatching {
            context.contentResolver.call(Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null,
                Bundle().apply { putString("package", context.packageName) })?.getBoolean("canShowFocus", false) == true
        }.getOrDefault(false)
        return HyperIslandStatus(protocol, allowed)
    }

    fun parameters(title: String, message: String, urgent: Boolean, island: Boolean): JSONObject {
        fun obj(vararg entries: Pair<String, Any>) = JSONObject().apply { entries.forEach { put(it.first, it.second) } }
        val pic = obj("type" to 1, "pic" to "miui.focus.pic_agent")
        val params = obj("protocol" to 1, "business" to "phone_agent", "updatable" to true,
            "islandFirstFloat" to urgent, "enableFloat" to urgent, "filterWhenNoPermission" to false,
            "timeout" to 10, "ticker" to title.take(20), "aodTitle" to title.take(40),
            "baseInfo" to obj("type" to 2, "title" to title.take(40), "content" to message.take(140)),
            "picInfo" to pic,
            "tickerPic" to "miui.focus.pic_agent", "aodPic" to "miui.focus.pic_agent")
        if (island) params.put("param_island", obj("islandProperty" to if (urgent) 2 else 1,
            "islandTimeout" to 120, "islandOrder" to urgent,
            "bigIslandArea" to obj("imageTextInfoLeft" to obj("type" to 1, "picInfo" to pic,
                "textInfo" to obj("title" to title.take(8), "content" to message.take(8))), "picInfo" to pic),
            "smallIslandArea" to obj("picInfo" to pic)))
        return obj("param_v2" to params)
    }

    fun extras(context: Context, status: HyperIslandStatus, title: String, message: String, urgent: Boolean,
        actions: List<Pair<String, Notification.Action>> = emptyList()): Bundle? {
        if (!status.allowed || status.protocol < 2) return null
        val parameters = parameters(title, message, urgent, status.protocol >= 3)
        if (actions.isNotEmpty()) parameters.getJSONObject("param_v2").put("actions",
            org.json.JSONArray(actions.map { JSONObject().put("action", it.first) }))
        val serialized = parameters.toString()
        if (serialized.toByteArray(Charsets.UTF_8).size > 3072) return null
        return Bundle().apply {
            putString("miui.focus.param", serialized)
            putBundle("miui.focus.pics", Bundle().apply {
                putParcelable("miui.focus.pic_agent", Icon.createWithResource(context,
                    context.applicationInfo.icon.takeIf { it != 0 } ?: android.R.drawable.ic_dialog_info))
            })
            if (actions.isNotEmpty()) putBundle("miui.focus.actions", Bundle().apply {
                actions.forEach { putParcelable(it.first, it.second) }
            })
        }
    }
}
