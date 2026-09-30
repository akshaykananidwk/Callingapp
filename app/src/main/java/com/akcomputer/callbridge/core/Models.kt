package com.akcomputer.callbridge.core

data class Segment(val text: String, val offsetMs: Long)

data class ActiveCall(
    val localId: String,
    val number: String?,
    val direction: String,
    val startedAt: Long,
    val serverId: String? = null,
    val audioSource: String? = null,
)

data class CallRecord(
    val id: String,
    val serverId: String?,
    val number: String?,
    val direction: String,
    val startedAt: Long,
    val endedAt: Long,
    val durationSec: Int,
    val synced: Boolean,
    val segments: List<Segment>,
) {
    val transcriptText: String get() = segments.joinToString(" ") { it.text }
}

/** A call row coming from the VPS (GET /calls, /calls/search). */
data class RemoteCall(
    val id: String,
    val number: String?,
    val direction: String?,
    val startedAt: String?,
    val durationSec: Int?,
    val snippet: String?,
)

enum class SocketState { IDLE, CONNECTING, CONNECTED, RECONNECTING }
