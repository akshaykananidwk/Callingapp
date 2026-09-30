package com.akcomputer.callbridge.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import com.akcomputer.callbridge.core.LiveState
import com.akcomputer.callbridge.core.Net
import com.akcomputer.callbridge.core.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject

/**
 * Always-on connection to the server (/device). Reports call state so the website shows
 * incoming calls instantly, and executes website commands: dial, answer, hangup, speaker.
 */
class ControlSocket(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appVersion: String,
) {
    private var ws: WebSocket? = null
    @Volatile private var open = false
    @Volatile private var running = false
    private var reconnectJob: Job? = null
    private var attempt = 0
    private var lastState: JSONObject? = null

    fun start() {
        if (running) return
        running = true
        connect()
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        ws?.close(1000, "service stopped")
        ws = null
        open = false
    }

    fun onNetworkAvailable() {
        if (running && !open) { reconnectJob?.cancel(); attempt = 0; connect() }
    }

    fun sendState(state: String, number: String?, direction: String?) {
        val msg = JSONObject().put("type", "state").put("state", state)
            .put("number", number ?: JSONObject.NULL).put("direction", direction ?: JSONObject.NULL)
        lastState = msg
        send(msg)
    }

    fun log(message: String) {
        Log.i("ControlSocket", message)
        send(JSONObject().put("type", "log").put("message", message))
    }

    private fun send(msg: JSONObject) {
        if (open) ws?.send(msg.toString())
    }

    private fun connect() {
        if (Prefs.token.isBlank()) { scheduleReconnect(); return }
        val req = try {
            Request.Builder().url(Prefs.controlUrl).header("Authorization", "Bearer ${Prefs.token}").build()
        } catch (e: IllegalArgumentException) {
            return
        }
        ws = Net.client.newWebSocket(req, listener)
    }

    private fun scheduleReconnect() {
        if (!running) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay((2000L shl attempt.coerceAtMost(4)).coerceAtMost(30_000L))
            attempt++
            if (running && !open) connect()
        }
    }

    private fun hello() = JSONObject().apply {
        put("type", "hello")
        put("app_version", appVersion)
        put("battery", context.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
        put("network", networkType())
        put("auto_answer", Prefs.autoAnswer)
        put("permissions", JSONArray(CallControl.missingPermissions(context)))
    }

    private fun networkType(): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    private fun handle(cmd: JSONObject) {
        val id = cmd.optString("id")
        val result = when (cmd.optString("type")) {
            "dial" -> {
                val n = cmd.optString("number")
                if (CallBridgeService.instance?.hasActiveCall() == true) CallControl.Result(false, "Phone is already on a call")
                else CallControl.dial(context, n, ::log)
            }
            "answer" -> CallControl.answer(context, ::log)
            "hangup" -> CallControl.hangup(context, ::log)
            "speaker" -> CallControl.speaker(context, cmd.optBoolean("on", true))
            else -> CallControl.Result(false, "Unknown command")
        }
        send(JSONObject().put("type", "ack").put("id", id).put("ok", result.ok).put("error", result.error ?: JSONObject.NULL))
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (webSocket !== ws) return
            open = true
            attempt = 0
            LiveState.serverOnline.value = true
            webSocket.send(hello().toString())
            lastState?.let { webSocket.send(it.toString()) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val cmd = try { JSONObject(text) } catch (e: Exception) { return }
            // Telephony calls must run on the main thread on some OEM builds.
            scope.launch(Dispatchers.Main) { handle(cmd) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (webSocket !== ws) return
            open = false
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (webSocket !== ws) return
            open = false
            if (response?.code == 401) LiveState.lastError.value = "Server rejected the API token"
            scheduleReconnect()
        }
    }
}
