package com.chuckiehelper.mobile.phonecontrol

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit notification actions resolve the corresponding assistance request. */
class PhoneAgentReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("notice_id") ?: return
        PhoneEngine.get(context).agentUi.reply(id, intent.getStringExtra("reply").orEmpty())
    }
}
