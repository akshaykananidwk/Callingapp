package com.akcomputer.callbridge.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.akcomputer.callbridge.core.Prefs

/** Receives PHONE_STATE (RINGING/OFFHOOK/IDLE) and NEW_OUTGOING_CALL broadcasts. */
class CallStateReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (!Prefs.serviceEnabled) return
        val service = CallBridgeService.instance
        when (intent.action) {
            Intent.ACTION_NEW_OUTGOING_CALL -> {
                val number = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)
                if (service != null) service.onOutgoingNumber(number)
                else forward(context, CallBridgeService.ACTION_OUTGOING, null, number)
            }
            TelephonyManager.ACTION_PHONE_STATE_CHANGED -> {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (service != null) service.onPhoneState(state, number)
                else forward(context, CallBridgeService.ACTION_PHONE_STATE, state, number)
            }
        }
    }

    private fun forward(context: Context, action: String, state: String?, number: String?) {
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, CallBridgeService::class.java)
                    .setAction(action)
                    .putExtra(CallBridgeService.EXTRA_STATE, state)
                    .putExtra(CallBridgeService.EXTRA_NUMBER, number),
            )
        } catch (e: Exception) {
            Log.e("CallStateReceiver", "Could not start service from background", e)
            notifyActivate(context)
        }
    }
}
