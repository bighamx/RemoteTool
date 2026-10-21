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

class NativeActivity : ComponentActivity() {
    private val shares: ShareInboxModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) shares.receive(intent)
        setContent { NativeApp(viewModel(), shares) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        shares.receive(intent)
    }
}
