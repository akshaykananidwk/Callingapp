package com.akcomputer.callbridge.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.akcomputer.callbridge.core.Prefs
import com.akcomputer.callbridge.service.CallBridgeService

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SCREEN = "screen"
        const val EXTRA_AUTOSTART = "autostart"
    }

    private val requestedScreen = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            CallBridgeTheme { AppRoot(requestedScreen) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        // Re-arm the service whenever the app is in the foreground (mic "while-in-use" rule).
        if (Prefs.serviceEnabled && CallBridgeService.instance == null && hasCorePermissions(this)) {
            runCatching { CallBridgeService.start(this) }
        }
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_SCREEN)?.let { requestedScreen.value = it }
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false) && Prefs.serviceEnabled) {
            runCatching { CallBridgeService.start(this) }
        }
    }
}
