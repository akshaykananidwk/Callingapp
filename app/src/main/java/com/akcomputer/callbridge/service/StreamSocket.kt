package com.akcomputer.callbridge.service

import android.util.Log
import com.akcomputer.callbridge.core.LiveState
import com.akcomputer.callbridge.core.Net
import com.akcomputer.callbridge.core.Prefs
import com.akcomputer.callbridge.core.SocketState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * One WebSocket per call (PRD section 08). Audio chunks are buffered while
 * disconnected and flushed on reconnect so a WiFi→4G switch loses no audio.
 */
class StreamSocket(
    private val scope: CoroutineScope,
    private val onTranscript: (text: String, ts: Long) -> Unit,
) {
    companion object {
        private const val TAG = "StreamSocket"
        private const val MAX_PENDING = 9_000 // 30 minutes of 200 ms chunks (~57 MB worst case)
    }

    private val lock = Any()
    private val pending = ArrayDeque<ByteArray>()
    private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var active = true
    @Volatile private var serverClosed = false
    private var callId: String? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    fun connect(id: String) {
        callId = id
        openSocket()
    }

    fun send(chunk: ByteArray) {
        synchronized(lock) {
            pending.addLast(chunk)
            while (pending.size > MAX_PENDING) pending.removeFirst()
            flushLocked()
        }
    }

    fun onNetworkAvailable() {
        if (active && !open && callId != null) {
            reconnectJob?.cancel()
            attempt = 0
            openSocket()
        }
    }

    /** Flush remaining audio, send the end signal and wait briefly for final transcripts. */
    suspend fun finish(id: String) {
        // Give an offline socket a chance to come back so buffered audio is not lost.
        var waited = 0
        while (!open && waited < 10_000) { delay(250); waited += 250 }
        synchronized(lock) { flushLocked() }
        active = false
        reconnectJob?.cancel()
        val socket = ws
        if (socket != null && open) {
            socket.send(JSONObject().put("type", "end").put("call_id", id).toString())
            waited = 0
            while (!serverClosed && waited < 5_000) { delay(200); waited += 200 }
            socket.close(1000, "call ended")
        }
        LiveState.socketState.value = SocketState.IDLE
    }

    fun abandon() {
        active = false
        reconnectJob?.cancel()
        ws?.cancel()
        LiveState.socketState.value = SocketState.IDLE
    }

    private fun flushLocked() {
        val socket = ws ?: return
        if (!open) return
        while (pending.isNotEmpty()) {
            if (!socket.send(pending.first().toByteString())) break
            pending.removeFirst()
        }
    }

    private fun openSocket() {
        val id = callId ?: return
        LiveState.socketState.value = if (attempt == 0) SocketState.CONNECTING else SocketState.RECONNECTING
        val req = try {
            Request.Builder()
                .url(Prefs.streamUrl)
                .header("Authorization", "Bearer ${Prefs.token}")
                .header("X-Call-ID", id)
                .header("X-Language", Prefs.language)
                .build()
        } catch (e: IllegalArgumentException) {
            LiveState.lastError.value = "Invalid stream URL: ${Prefs.streamUrl}"
            return
        }
        ws = Net.client.newWebSocket(req, listener)
    }

    private fun scheduleReconnect() {
        if (!active) return
        LiveState.socketState.value = SocketState.RECONNECTING
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val backoff = (1000L shl attempt.coerceAtMost(4)).coerceAtMost(15_000L)
            attempt++
            delay(backoff)
            if (active && !open) openSocket()
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            open = true
            attempt = 0
            LiveState.socketState.value = SocketState.CONNECTED
            LiveState.lastError.value = null
            synchronized(lock) { flushLocked() }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = JSONObject(text)
                when (o.optString("type")) {
                    "transcript" -> {
                        val t = o.optString("text").trim()
                        if (t.isNotEmpty()) onTranscript(t, o.optLong("ts", -1))
                    }
                    "error" -> LiveState.lastError.value = o.optString("message", text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bad message: $text")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            serverClosed = true
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            open = false
            serverClosed = true
            if (active) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            open = false
            Log.w(TAG, "WebSocket failure: ${t.message}")
            LiveState.lastError.value = "Stream: ${t.message ?: t.javaClass.simpleName}"
            if (active) scheduleReconnect() else serverClosed = true
        }
    }
}
