package com.chuckiehelper.mobile

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chuckiehelper.mobile.nativeui.NativeApp
import com.chuckiehelper.mobile.nativeui.receiveEditionIntent
import com.chuckiehelper.mobile.nativeui.CloudAccountModel
import com.chuckiehelper.mobile.nativeui.ImeEditingCompat

class NativeActivity : ComponentActivity() {
    private val cloud: CloudAccountModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && !receiveCloud(intent)) receiveEditionIntent(this, intent)
        setContent { ImeEditingCompat { NativeApp(viewModel()) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!receiveCloud(intent)) receiveEditionIntent(this, intent)
    }

    private fun receiveCloud(intent: Intent): Boolean =
        com.chuckiehelper.mobile.nativeui.CloudCallbackRouter.forwardIfNeeded(this, intent.data) || cloud.receiveCallback(intent.data)
}
