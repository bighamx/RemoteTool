package com.chuckiehelper.mobile

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chuckiehelper.mobile.nativeui.NativeApp
import com.chuckiehelper.mobile.nativeui.ShareInboxModel
import com.chuckiehelper.mobile.nativeui.CloudAccountModel
import com.chuckiehelper.mobile.nativeui.ImeEditingCompat

class NativeActivity : ComponentActivity() {
    private val shares: ShareInboxModel by viewModels()
    private val cloud: CloudAccountModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && !receiveCloud(intent) && !BuildConfig.PHONE_COMPANION) shares.receive(intent)
        setContent { ImeEditingCompat { NativeApp(viewModel(), if (BuildConfig.PHONE_COMPANION) null else shares) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!receiveCloud(intent) && !BuildConfig.PHONE_COMPANION) shares.receive(intent)
    }

    private fun receiveCloud(intent: Intent): Boolean =
        com.chuckiehelper.mobile.nativeui.CloudCallbackRouter.forwardIfNeeded(this, intent.data) || cloud.receiveCallback(intent.data)
}
