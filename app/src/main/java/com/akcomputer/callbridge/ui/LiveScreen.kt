package com.akcomputer.callbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.akcomputer.callbridge.core.LiveState
import com.akcomputer.callbridge.core.SocketState
import kotlinx.coroutines.delay

@Composable
fun LiveScreen() {
    val call by LiveState.call.collectAsState()
    val transcript by LiveState.transcript.collectAsState()
    val level by LiveState.audioLevel.collectAsState()
    val socket by LiveState.socketState.collectAsState()
    val error by LiveState.lastError.collectAsState()

    val c = call
    if (c == null) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "No active call.\nThe live transcript opens automatically when a call starts.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(c.localId) {
        while (true) { now = System.currentTimeMillis(); delay(1000) }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(transcript.size) {
        if (transcript.isNotEmpty()) listState.animateScrollToItem(transcript.size - 1)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(c.number ?: "Unknown number", style = MaterialTheme.typography.headlineSmall)
        Text(
            "${c.direction.replaceFirstChar { it.uppercase() }} · ${formatDuration(((now - c.startedAt) / 1000).toInt())}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusDot(
                when (socket) {
                    SocketState.CONNECTED -> Green
                    SocketState.IDLE -> Grey
                    else -> Amber
                },
                "Stream ${socket.name.lowercase()}",
            )
            c.audioSource?.let { Text("Mic: $it", style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(8.dp))
        Text("Audio level", style = MaterialTheme.typography.labelSmall)
        LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(6.dp))
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp))
        }
        SectionTitle("Live transcript")
        if (transcript.isEmpty()) {
            Text("Listening…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
            items(transcript) { seg ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text(
                        formatOffset(seg.offsetMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(end = 10.dp, top = 2.dp),
                    )
                    Text(seg.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
