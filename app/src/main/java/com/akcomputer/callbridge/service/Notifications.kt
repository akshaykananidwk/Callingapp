package com.akcomputer.callbridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.akcomputer.callbridge.R
import com.akcomputer.callbridge.core.ActiveCall
import com.akcomputer.callbridge.ui.MainActivity

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_LIVE = "live"
    const val CH_ALERT = "alert"
    const val ID_SERVICE = 1
    const val ID_LIVE = 2
    const val ID_ACTIVATE = 3

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "CallBridge service", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Persistent notification while CallBridge is active"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_LIVE, "Live call", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Opens the live transcript when a call starts"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Alerts", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(context: Context, screen: String?, autostart: Boolean = false, req: Int = 0): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (screen != null) putExtra(MainActivity.EXTRA_SCREEN, screen)
            if (autostart) putExtra(MainActivity.EXTRA_AUTOSTART, true)
        }
        return PendingIntent.getActivity(
            context, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    fun service(context: Context, call: ActiveCall?, lastLine: String?, online: Boolean?) =
        NotificationCompat.Builder(context, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_callbridge)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .apply {
                if (call == null) {
                    setContentTitle("CallBridge Active")
                    setContentText(
                        when (online) {
                            true -> "Waiting for calls · VPS connected"
                            false -> "Waiting for calls · VPS offline"
                            null -> "Waiting for calls"
                        }
                    )
                    setContentIntent(openApp(context, null, req = 10))
                } else {
                    val who = call.number ?: "Unknown number"
                    setContentTitle("${if (call.direction == "incoming") "Incoming" else "Outgoing"} · $who")
                    setContentText(lastLine ?: "Listening…")
                    setStyle(NotificationCompat.BigTextStyle().bigText(lastLine ?: "Listening…"))
                    setUsesChronometer(true)
                    setWhen(call.startedAt)
                    setShowWhen(true)
                    setContentIntent(openApp(context, "live", req = 11))
                }
            }
            .build()

    /** Heads-up / full-screen notification that opens the live transcript screen. */
    fun liveCall(context: Context, call: ActiveCall) =
        NotificationCompat.Builder(context, CH_LIVE)
            .setSmallIcon(R.drawable.ic_stat_callbridge)
            .setContentTitle("Live transcript")
            .setContentText(call.number ?: "Call in progress")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context, "live", req = 12))
            .setFullScreenIntent(openApp(context, "live", req = 13), true)
            .build()

    fun activate(context: Context) =
        NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_callbridge)
            .setContentTitle("Tap to resume CallBridge")
            .setContentText("Android needs one tap after restart to allow microphone access.")
            .setAutoCancel(true)
            .setContentIntent(openApp(context, null, autostart = true, req = 14))
            .build()
}
