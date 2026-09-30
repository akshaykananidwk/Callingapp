package com.akcomputer.callbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.akcomputer.callbridge.core.Prefs

/** Restarts the service after reboot / app update when the user left it enabled. */
class BootReceiver : BroadcastReceiver() {
    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS || !Prefs.serviceEnabled) return
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 14+ forbids starting a microphone foreground service from BOOT_COMPLETED;
            // one tap on this notification brings the service back with mic access.
            notifyActivate(context)
            return
        }
        try {
            ContextCompat.startForegroundService(
                context, Intent(context, CallBridgeService::class.java).setAction(CallBridgeService.ACTION_START)
            )
        } catch (e: Exception) {
            Log.e("BootReceiver", "auto-start failed", e)
            notifyActivate(context)
        }
    }
}
