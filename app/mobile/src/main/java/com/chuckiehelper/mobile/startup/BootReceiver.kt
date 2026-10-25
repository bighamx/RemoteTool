package com.chuckiehelper.mobile.startup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        if (!context.getSystemService(android.os.UserManager::class.java).isUserUnlocked) return
        if (StartupSettings(context).enabled) StartupService.request(context)
    }
}
