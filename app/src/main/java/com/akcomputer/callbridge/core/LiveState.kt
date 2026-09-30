package com.akcomputer.callbridge.core

import kotlinx.coroutines.flow.MutableStateFlow

/** Process-wide live state shared between the service and the UI. */
object LiveState {
    val serviceRunning = MutableStateFlow(false)
    val serverOnline = MutableStateFlow<Boolean?>(null)
    val lastHeartbeatAt = MutableStateFlow(0L)
    val call = MutableStateFlow<ActiveCall?>(null)
    val transcript = MutableStateFlow<List<Segment>>(emptyList())
    val audioLevel = MutableStateFlow(0f)
    val socketState = MutableStateFlow(SocketState.IDLE)
    val lastError = MutableStateFlow<String?>(null)
}
