package com.akcomputer.callbridge.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.akcomputer.callbridge.core.Api
import com.akcomputer.callbridge.core.LiveState
import com.akcomputer.callbridge.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Foreground service (type microphone). Monitors call state, runs a [CallSession]
 * per call, sends heartbeats and reconnects streaming on network changes.
 */
class CallBridgeService : Service() {

    companion object {
        private const val TAG = "CallBridgeService"
        const val ACTION_START = "com.akcomputer.callbridge.START"
        const val ACTION_STOP = "com.akcomputer.callbridge.STOP"
        const val ACTION_PHONE_STATE = "com.akcomputer.callbridge.PHONE_STATE"
        const val ACTION_OUTGOING = "com.akcomputer.callbridge.OUTGOING"
        const val EXTRA_STATE = "state"
        const val EXTRA_NUMBER = "number"

        @Volatile var instance: CallBridgeService? = null
            private set

        /** Start from a foreground context (activity / notification tap). */
        fun start(context: Context) {
            Prefs.serviceEnabled = true
            ContextCompat.startForegroundService(
                context, Intent(context, CallBridgeService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            Prefs.serviceEnabled = false
            context.startService(Intent(context, CallBridgeService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: CallSession? = null
    private var ringing = false
    private var ringNumber: String? = null
    private var outgoingNumber: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var autoAnswerJob: Job? = null
    private lateinit var control: ControlSocket
    private var demoJob: Job? = null
    private var inForeground = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            session?.onNetworkAvailable()
            control.onNetworkAvailable()
            scope.launch { heartbeatOnce() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        LiveState.serviceRunning.value = true
        val version = try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (e: Exception) { "" }
        control = ControlSocket(this, scope, version)
        control.start()
        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        startHeartbeat()
        observeForNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_PHONE_STATE -> onPhoneState(intent.getStringExtra(EXTRA_STATE), intent.getStringExtra(EXTRA_NUMBER))
            ACTION_OUTGOING -> onOutgoingNumber(intent.getStringExtra(EXTRA_NUMBER))
        }
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        LiveState.serviceRunning.value = false
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        val s = session
        session = null
        if (s != null) CoroutineScope(Dispatchers.Default).launch { s.stop() }
        releaseWakeLock()
        control.stop()
        DemoMode.stopPlayback(this)
        scope.cancel()
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        if (inForeground) return true
        return try {
            ServiceCompat.startForeground(
                this, Notifications.ID_SERVICE,
                Notifications.service(this, LiveState.call.value, null, LiveState.serverOnline.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            inForeground = true
            true
        } catch (e: Exception) {
            // Android 14+: a microphone FGS cannot be started from the background.
            Log.e(TAG, "startForeground failed", e)
            notifyActivate(this)
            stopSelf()
            false
        }
    }

    // ---- Call state machine: RINGING → OFFHOOK → IDLE -------------------------------------

    fun hasActiveCall() = session != null

    fun onOutgoingNumber(number: String?) {
        if (!number.isNullOrBlank()) {
            outgoingNumber = number
            session?.takeIf { it.direction == "outgoing" }?.updateNumber(number)
        }
    }

    fun onPhoneState(state: String?, number: String?) {
        Log.i(TAG, "Phone state $state")
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (session == null) {
                    ringing = true
                    if (!number.isNullOrBlank()) ringNumber = number
                    control.sendState("ringing", ringNumber, "incoming")
                    if (Prefs.autoAnswer && autoAnswerJob == null) {
                        autoAnswerJob = scope.launch(Dispatchers.Main) {
                            delay(Prefs.demoAnswerDelaySec * 1000L)
                            // The number usually arrives in a second broadcast, so decide at answer time.
                            if (ringing && session == null) {
                                if (DemoMode.numberAllowed(ringNumber)) {
                                    control.log("Auto-answering ${ringNumber ?: "unknown number"}")
                                    CallControl.answer(this@CallBridgeService, control::log)
                                } else {
                                    control.log("Not auto-answering ${ringNumber ?: "unknown number"} (not in the number list)")
                                }
                            }
                        }
                    } else if (!Prefs.autoAnswer && number.isNullOrBlank()) {
                        control.log("Auto-answer is OFF in the app")
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val current = session
                if (current != null) {
                    current.updateNumber(number)
                    if (!number.isNullOrBlank()) control.sendState("offhook", current.number, current.direction)
                    return
                }
                val direction = if (ringing) "incoming" else "outgoing"
                val num = (if (ringing) ringNumber else outgoingNumber) ?: number
                acquireWakeLock()
                val s = CallSession(applicationContext, scope, num, direction)
                session = s
                s.start()
                control.sendState("offhook", num, direction)
                onCallStarted()
                autoAnswerJob?.cancel()
                autoAnswerJob = null
                if (DemoMode.appliesTo(num, incoming = direction == "incoming")) {
                    demoJob = scope.launch(Dispatchers.Main) {
                        delay(1500) // let the call audio path settle
                        DemoMode.startPlayback(this@CallBridgeService)
                    }
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                val s = session
                session = null
                ringing = false
                ringNumber = null
                outgoingNumber = null
                control.sendState("idle", null, null)
                autoAnswerJob?.cancel()
                autoAnswerJob = null
                demoJob?.cancel()
                demoJob = null
                DemoMode.stopPlayback(this)
                NotificationManagerCompat.from(this).cancel(Notifications.ID_LIVE)
                if (s != null) scope.launch {
                    s.stop()
                    releaseWakeLock()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun onCallStarted() {
        val call = LiveState.call.value ?: return
        if (Prefs.autoOpenLive && canNotify()) {
            NotificationManagerCompat.from(this).notify(Notifications.ID_LIVE, Notifications.liveCall(this, call))
        }
    }

    // ---- Notification updates -----------------------------------------------------------

    @OptIn(FlowPreview::class)
    private fun observeForNotification() {
        scope.launch {
            combine(LiveState.call, LiveState.transcript, LiveState.serverOnline) { c, t, o -> Triple(c, t, o) }
                .debounce(400)
                .collect { (call, transcript, online) ->
                    val line = if (Prefs.liveNotification) transcript.takeLast(3).joinToString(" ") { it.text }
                        .takeIf { it.isNotBlank() } else null
                    if (inForeground) {
                        getSystemService(NotificationManager::class.java)
                            .notify(Notifications.ID_SERVICE, Notifications.service(this@CallBridgeService, call, line, online))
                    }
                }
        }
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    // ---- Heartbeat (PRD: every 60 s) ----------------------------------------------------------

    private fun startHeartbeat() {
        scope.launch {
            while (true) {
                heartbeatOnce()
                delay(60_000)
            }
        }
    }

    private suspend fun heartbeatOnce() {
        if (Prefs.token.isBlank()) return
        val battery = getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = if (session != null) "in_call" else "active"
        try {
            Api.heartbeat(battery, networkType(), status)
            LiveState.serverOnline.value = true
            LiveState.lastHeartbeatAt.value = System.currentTimeMillis()
        } catch (e: Exception) {
            Log.w(TAG, "heartbeat failed: ${e.message}")
            LiveState.serverOnline.value = false
        }
    }

    private fun networkType(): String {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }

    // ---- Wake lock ------------------------------------------------------------------------

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CallBridge:call")
            .apply { acquire(4 * 60 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wakeLock = null
    }
}

@SuppressLint("MissingPermission")
fun notifyActivate(context: Context) {
    if (Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    NotificationManagerCompat.from(context).notify(Notifications.ID_ACTIVATE, Notifications.activate(context))
}
