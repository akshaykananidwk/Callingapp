package com.akcomputer.callbridge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.akcomputer.callbridge.core.CallRecord
import com.akcomputer.callbridge.core.LiveState
import com.akcomputer.callbridge.core.LocalHistory
import com.akcomputer.callbridge.core.Prefs
import com.akcomputer.callbridge.core.SocketState
import com.akcomputer.callbridge.service.CallBridgeService

@Composable
fun HomeScreen(onOpenRecord: (CallRecord) -> Unit, onOpenLive: () -> Unit) {
    val ctx = LocalContext.current
    val running by LiveState.serviceRunning.collectAsState()
    val online by LiveState.serverOnline.collectAsState()
    val call by LiveState.call.collectAsState()
    val socket by LiveState.socketState.collectAsState()
    val error by LiveState.lastError.collectAsState()
    val records by LocalHistory.records.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val permsOk = remember(refresh) { hasCorePermissions(ctx) }
    val batteryOk = remember(refresh) { isIgnoringBatteryOptimizations(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("CallBridge", style = MaterialTheme.typography.headlineMedium)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Service", style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (running) "Active — calls are transcribed" else "Stopped",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = running,
                        enabled = permsOk,
                        onCheckedChange = { on ->
                            if (on) CallBridgeService.start(ctx) else CallBridgeService.stop(ctx)
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
                StatusDot(
                    when (online) { true -> Green; false -> Red; null -> Grey },
                    when (online) {
                        true -> "VPS connected"
                        false -> "VPS offline"
                        null -> if (Prefs.token.isBlank()) "API token not set" else "VPS status unknown"
                    },
                )
                if (call != null) {
                    Spacer(Modifier.height(6.dp))
                    StatusDot(
                        when (socket) {
                            SocketState.CONNECTED -> Green
                            SocketState.IDLE -> Grey
                            else -> Amber
                        },
                        "Stream: ${socket.name.lowercase()}",
                    )
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (!permsOk || !batteryOk) {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Setup needed", style = MaterialTheme.typography.titleMedium)
                    if (!permsOk) {
                        Text("Phone and microphone permissions are required.")
                        OutlinedButton(onClick = { openAppSettings(ctx) }) { Text("Open app permissions") }
                    }
                    if (!batteryOk) {
                        Text("Battery optimization is on — Android may stop CallBridge in the background.")
                        OutlinedButton(onClick = { requestIgnoreBatteryOptimizations(ctx) }) {
                            Text("Disable battery optimization")
                        }
                    }
                }
            }
        }

        call?.let { c ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Call in progress", style = MaterialTheme.typography.titleMedium)
                    Text("${c.direction.replaceFirstChar { it.uppercase() }} · ${c.number ?: "Unknown number"}")
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onOpenLive) { Text("Open live transcript") }
                }
            }
        }

        SectionTitle("Recent transcripts")
        if (records.isEmpty()) {
            Text("No calls yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Card(Modifier.fillMaxWidth()) {
                records.take(5).forEachIndexed { i, r ->
                    if (i > 0) HorizontalDivider()
                    CallRow(r.title(), r.subtitle(), r.transcriptText.ifBlank { "(no transcript)" }) { onOpenRecord(r) }
                }
            }
        }
    }
}
