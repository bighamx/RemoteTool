package com.chuckiehelper.mobile.nativeui

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.chuckiehelper.mobile.background.BackgroundNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class NotificationTestService : Service() { override fun onBind(intent: Intent?): IBinder? = null }

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class BackgroundNotificationTest {
    @Test fun anEndingAgentDismissesItsIslandWithoutStoppingOtherFeatures() {
        val phone = Robolectric.buildService(NotificationTestService::class.java).create().get()
        val tunnel = Robolectric.buildService(NotificationTestService::class.java).create().get()
        val manager = phone.getSystemService(NotificationManager::class.java)
        try {
            BackgroundNotification.start(phone, "phone-tools", "手机操控")
            BackgroundNotification.start(tunnel, "port-tunnel", "端口转发")
            val focus = android.os.Bundle().apply { putString("miui.focus.param",
                """{"param_v2":{"param_island":{"bigIslandArea":{},"smallIslandArea":{}}}}""") }
            BackgroundNotification.update(phone, "phone-tools", "Agent 操作", focus)
            BackgroundNotification.update(phone, "phone-tools", "手机操控")
            val notification = shadowOf(manager).allNotifications.single()
            val island = org.json.JSONObject(notification.extras.getString("miui.focus.param")!!)
                .getJSONObject("param_v2").getJSONObject("param_island")
            assertTrue(island.getBoolean("dismissIsland"))
            assertTrue(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("端口转发"))
        } finally { BackgroundNotification.finish(phone, "phone-tools"); BackgroundNotification.finish(tunnel, "port-tunnel") }
    }
    @Test fun independentServicesShareOneNotificationAndSurvivePartialStops() {
        val phone = Robolectric.buildService(NotificationTestService::class.java).create().get()
        val agent = Robolectric.buildService(NotificationTestService::class.java).create().get()
        val tunnel = Robolectric.buildService(NotificationTestService::class.java).create().get()
        val manager = phone.getSystemService(NotificationManager::class.java)
        fun summary() = shadowOf(manager).allNotifications.single().extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        try {
            BackgroundNotification.start(phone, "phone-tools", "手机工具")
            BackgroundNotification.start(agent, "agent-watch", "会话保持")
            BackgroundNotification.start(tunnel, "port-tunnel", "端口转发")
            assertEquals(1, shadowOf(manager).allNotifications.size)
            assertTrue(summary().contains("手机工具") && summary().contains("会话保持") && summary().contains("端口转发"))
            BackgroundNotification.finish(phone, "phone-tools")
            assertEquals(1, shadowOf(manager).allNotifications.size)
            assertFalse(summary().contains("手机工具"))
            BackgroundNotification.finish(tunnel, "port-tunnel")
            assertEquals("会话保持", summary())
            BackgroundNotification.finish(agent, "agent-watch")
            assertTrue(shadowOf(manager).allNotifications.isEmpty())
        } finally {
            BackgroundNotification.finish(phone, "phone-tools"); BackgroundNotification.finish(agent, "agent-watch"); BackgroundNotification.finish(tunnel, "port-tunnel")
        }
    }
}
