package com.akcomputer.callbridge.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.akcomputer.callbridge.core.ActiveCall
import com.akcomputer.callbridge.core.Api
import com.akcomputer.callbridge.core.CallRecord
import com.akcomputer.callbridge.core.LiveState
import com.akcomputer.callbridge.core.LocalHistory
import com.akcomputer.callbridge.core.Prefs
import com.akcomputer.callbridge.core.Segment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** Lifecycle of one phone call: register with VPS, stream audio, collect transcript, finalize. */
class CallSession(
    private val context: Context,
    private val scope: CoroutineScope,
    initialNumber: String?,
    val direction: String,
) {
    private val localId = UUID.randomUUID().toString()
    private val startedAt = System.currentTimeMillis()
    @Volatile var number: String? = initialNumber
        private set
    @Volatile private var serverId: String? = null
    private val segments = mutableListOf<Segment>()
    private var audio: AudioCapture? = null
    private var registerJob: Job? = null

    private val socket = StreamSocket(scope) { text, ts ->
        val offset = when {
            ts < 0 -> System.currentTimeMillis() - startedAt
            ts > 100_000_000_000L -> ts - startedAt // absolute epoch ms
            else -> ts
        }
        val seg = Segment(text, offset)
        synchronized(segments) { segments.add(seg) }
        LiveState.transcript.value = LiveState.transcript.value + seg
    }

    fun start() {
        LiveState.transcript.value = emptyList()
        LiveState.lastError.value = null
        val capture = AudioCapture(
            Prefs.audioSource,
            onChunk = { socket.send(it) },
            onLevel = { LiveState.audioLevel.value = it },
        )
        if (!capture.start()) LiveState.lastError.value = "Microphone unavailable during call"
        audio = capture
        publish()

        registerJob = scope.launch {
            delay(800) // the caller number usually arrives in a second PHONE_STATE broadcast
            var backoff = 1000L
            while (isActive && serverId == null) {
                try {
                    serverId = Api.startCall(number, direction, startedAt)
                } catch (e: Exception) {
                    Log.w("CallSession", "start failed: ${e.message}")
                    LiveState.lastError.value = "Server: ${e.message}"
                    delay(backoff)
                    backoff = (backoff * 2).coerceAtMost(15_000)
                }
            }
            serverId?.let {
                publish()
                socket.connect(it)
            }
        }
    }

    fun updateNumber(n: String?) {
        if (!n.isNullOrBlank() && number.isNullOrBlank()) {
            number = n
            publish()
        }
    }

    fun onNetworkAvailable() = socket.onNetworkAvailable()

    suspend fun stop() {
        audio?.stop()
        LiveState.audioLevel.value = 0f
        registerJob?.cancel()
        val endedAt = System.currentTimeMillis()
        val duration = ((endedAt - startedAt) / 1000).toInt()
        if (number.isNullOrBlank()) number = lastCallLogNumber()

        val id = serverId
        var synced = false
        if (id != null) {
            socket.finish(id)
            synced = try {
                Api.endCall(id, duration, number); true
            } catch (e: Exception) {
                LiveState.lastError.value = "End call sync failed: ${e.message}"; false
            }
        } else {
            socket.abandon()
        }

        val segs = synchronized(segments) { segments.toList() }
        LocalHistory.add(
            CallRecord(localId, id, number, direction, startedAt, endedAt, duration, synced, segs)
        )
        LiveState.call.value = null
    }

    private fun publish() {
        LiveState.call.value = ActiveCall(localId, number, direction, startedAt, serverId, audio?.activeSource)
    }

    private fun lastCallLogNumber(): String? {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI, arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.DATE),
                "${CallLog.Calls.DATE} >= ?", arrayOf((startedAt - 120_000).toString()),
                "${CallLog.Calls.DATE} DESC",
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
